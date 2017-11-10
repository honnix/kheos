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

import com.spotify.apollo.Response
import com.spotify.apollo.Status
import com.spotify.apollo.Status.*
import com.spotify.apollo.entity.EntityMiddleware
import com.spotify.apollo.entity.JacksonEntityCodec
import com.spotify.apollo.route.AsyncHandler
import com.spotify.apollo.route.Middleware
import com.spotify.apollo.route.Route
import com.spotify.apollo.route.SyncHandler
import io.honnix.kheos.lib.CheckAccountResponse

import io.honnix.kheos.lib.ErrorId.*
import io.honnix.kheos.lib.GetPlayersResponse
import io.honnix.kheos.lib.HeosClient
import io.honnix.kheos.lib.HeosClientException
import io.honnix.kheos.lib.HeosCommandException
import io.honnix.kheos.lib.JSON
import okio.ByteString
import org.slf4j.LoggerFactory

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

internal fun <T> callAndBuildResponse(h: () -> Unit = {}, retries: Int = 3, f: () -> T): Response<T> = try {
  Response.forPayload(f())
} catch (e: HeosCommandException) {
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
            em.serializerResponse(GetPlayersResponse::class.java),
            "GET", base + "/players",
            SyncHandler { getPlayers() })

    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun checkAccount() = callAndBuildResponse(heosClient::reconnect) {
    heosClient.checkAccount()
  }

  private fun getPlayers() = callAndBuildResponse(heosClient::reconnect) {
    heosClient.getPlayers()
  }
}

class HeosPlayerCommandResource(heosClient: HeosClient) {
  fun routes(): List<KRoute> {
    val base = "/player"
    val em = EntityMiddleware.forCodec(JacksonEntityCodec.forMapper(JSON.mapper))

    return listOf()
  }
}
