/*-
 * -\-\-
 * kheos
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */
package io.honnix.kheos.service

import com.spotify.apollo.*
import com.spotify.apollo.Status.*
import com.spotify.apollo.entity.*
import com.spotify.apollo.route.*
import io.honnix.kheos.common.*
import io.honnix.kheos.lib.*
import io.honnix.kheos.lib.ErrorId.*
import javaslang.control.Try
import okio.ByteString
import org.slf4j.LoggerFactory
import java.util.function.Supplier

typealias KRoute = Route<out AsyncHandler<out Response<ByteString>>>

internal val eid2Status = mapOf(
    UNRECOGNIZED_COMMAND to BAD_REQUEST,
    INVALID_ID to BAD_REQUEST,
    WRONG_NUMBER_OF_COMMAND_ARGUMENTS to BAD_REQUEST,
    REQUESTED_DATA_NOT_AVAILABLE to UNPROCESSABLE_ENTITY,
    RESOURCE_CURRENTLY_NOT_AVAILABLE to UNPROCESSABLE_ENTITY,
    INVALID_CREDENTIALS to FORBIDDEN,
    COMMAND_COULD_NOT_BE_EXECUTED to UNPROCESSABLE_ENTITY,
    USER_NOT_LOGGED_IN to FORBIDDEN,
    PARAMETER_OUT_OF_RANGE to BAD_REQUEST,
    USER_NOT_FOUND to FORBIDDEN,
    INTERNAL_ERROR to INTERNAL_SERVER_ERROR,
    SYSTEM_ERROR to INTERNAL_SERVER_ERROR,
    PROCESSING_PREVIOUS_COMMAND to Status.createForCode(429),
    MEDIA_CANNOT_BE_PLAYED to UNSUPPORTED_MEDIA_TYPE,
    OPTION_NO_SUPPORTED to BAD_REQUEST
)

object Api {
  internal fun prefixRoutes(routes: List<KRoute>, vararg versions: Version) =
      versions.flatMap { v -> routes.map { r -> r.withPrefix(v.prefix()) } }

  enum class Version {
    V0;

    fun prefix() = "/api/${name.toLowerCase()}"
  }
}

internal val logger = LoggerFactory.getLogger(object {}::class.java.`package`.name)

internal fun <T> callAndBuildResponse(h: () -> Unit = {}, retries: Int = 3, f: () -> T)
    : Response<T> = try {
  Response.forPayload(f())
} catch (e: IllegalArgumentException) {
  logger.debug("bad request", e)
  Response.forStatus(Status.BAD_REQUEST.withReasonPhrase(e.message))
} catch (e: HeosCommandException) {
  logger.info("failed to execute command", e)
  Response.forStatus(eid2Status.getOrDefault(e.eid, INTERNAL_SERVER_ERROR).withReasonPhrase(e.message))
} catch (e: HeosClientException) {
  if (retries == 0) {
    logger.error("failed to send command after retries and this is unlikely to recover")
    Response.forStatus(INTERNAL_SERVER_ERROR.withReasonPhrase(e.message))
  } else {
    logger.warn("failed to send command, will retry $retries time${if (retries > 1) "s" else ""}", e)
    h()
    callAndBuildResponse(h, retries - 1, f)
  }
}

class HeosSystemCommandResource(private val heosClient: HeosClient) {
  fun routes(): List<KRoute> {
    val base = "/system"
    val em = EntityMiddleware.forCodec(JacksonEntityCodec.forMapper(JSON.mapper))

    val routes = listOf(
        Route.with(
            em.serializerResponse(CheckAccountResponse::class.java),
            "GET", base + "/account",
            SyncHandler { checkAccount() }),
        Route.with(
            em.serializerResponse(SignInResponse::class.java),
            "POST", base + "/account",
            SyncHandler { signIn(it) }),
        Route.with(
            em.serializerResponse(SignOutResponse::class.java),
            "DELETE", base + "/account",
            SyncHandler { signOut() }),
        Route.with(
            em.serializerResponse(RebootResponse::class.java),
            "PUT", base + "/state",
            SyncHandler { reboot() })
    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun checkAccount() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.checkAccount()
  }

  private fun signIn(rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val userName = rc.request().parameter("user_name").orElse(null)
    val password = rc.request().parameter("password").orElse(null)

    if (userName.isNullOrBlank() || password.isNullOrBlank()) {
      throw IllegalArgumentException("empty user_name or password")
    }

    heosClient.signIn(userName, password)
  }

  private fun signOut() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.signOut()
  }

  private fun reboot() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.reboot()
  }
}

class HeosPlayerCommandResource(private val heosClient: HeosClient) {
  fun routes(): List<KRoute> {
    val base = "/players"
    val em = EntityMiddleware.forCodec(JacksonEntityCodec.forMapper(JSON.mapper))

    val routes = listOf(
        Route.with(
            em.serializerResponse(GetPlayersResponse::class.java),
            "GET", base + "/players",
            SyncHandler { getPlayers() }),
        Route.with(
            em.serializerResponse(GetPlayerInfoResponse::class.java),
            "GET", base + "/players/<pid>",
            SyncHandler { getPlayerInfo(it.pathArgs()["pid"]!!) }),
        Route.with(
            em.serializerResponse(GetPlayStateResponse::class.java),
            "GET", base + "/players/<pid>/state",
            SyncHandler { getPlayState(it.pathArgs()["pid"]!!) }),
        Route.with(
            em.serializerResponse(SetPlayStateResponse::class.java),
            "PATCH", base + "/players/<pid>/state",
            SyncHandler { setPlayState(it.pathArgs()["pid"]!!, it) })
    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun getPlayers() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getPlayers()
  }

  private fun getPlayerInfo(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getPlayerInfo(pid)
  }

  private fun getPlayState(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getPlayState(pid)
  }

  private fun setPlayState(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val state = rc.request().parameter("state")
        .map { state ->
          println(state)
          Try.of { PlayState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid state") })
        }
        .orElseThrow({ IllegalArgumentException("missing state") })

    heosClient.setPlayState(pid, state)
  }
}
