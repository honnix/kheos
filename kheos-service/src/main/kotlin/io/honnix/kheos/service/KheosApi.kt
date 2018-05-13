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
            "GET", "$base/account",
            SyncHandler { checkAccount() }),
        Route.with(
            em.serializerResponse(SignInResponse::class.java),
            "POST", "$base/account",
            SyncHandler { signIn(it) }),
        Route.with(
            em.serializerResponse(SignOutResponse::class.java),
            "DELETE", "$base/account",
            SyncHandler { signOut() }),
        Route.with(
            em.serializerResponse(RebootResponse::class.java),
            "PUT", "$base/state",
            SyncHandler { reboot() })
    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun checkAccount() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.checkAccount()
  }

  private fun signIn(rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val userName = rc.request().parameter("user_name")
        .orElseThrow { IllegalArgumentException("missing user_name") }
    val password = rc.request().parameter("password")
        .orElseThrow { IllegalArgumentException("missing password") }

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
            "GET", base,
            SyncHandler { getPlayers() }),
        Route.with(
            em.serializerResponse(GetPlayerInfoResponse::class.java),
            "GET", "$base/<pid>",
            SyncHandler { getPlayerInfo(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(GetPlayStateResponse::class.java),
            "GET", "$base/<pid>/state",
            SyncHandler { getPlayState(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(SetPlayStateResponse::class.java),
            "PATCH", "$base/<pid>/state",
            SyncHandler { setPlayState(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(GetNowPlayingMediaResponse::class.java),
            "GET", "$base/<pid>/now_playing_media",
            SyncHandler { getNowPlayingMedia(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(GetVolumeResponse::class.java),
            "GET", "$base/<pid>/volume",
            SyncHandler { getVolume(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(SetVolumeResponse::class.java),
            "PATCH", "$base/<pid>/volume",
            SyncHandler { setVolume(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(VolumeUpResponse::class.java),
            "POST", "$base/<pid>/volume/up",
            SyncHandler { volumeUp(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(VolumeDownResponse::class.java),
            "POST", "$base/<pid>/volume/down",
            SyncHandler { volumeDown(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(GetMuteResponse::class.java),
            "GET", "$base/<pid>/mute",
            SyncHandler { getMute(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(GenericResponse::class.java),
            "PATCH", "$base/<pid>/mute",
            SyncHandler { setOrToggleMute(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(GetPlayModeResponse::class.java),
            "GET", "$base/<pid>/mode",
            SyncHandler { getPlayMode(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(SetPlayModeResponse::class.java),
            "PATCH", "$base/<pid>/mode",
            SyncHandler { setPlayMode(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(GetQueueResponse::class.java),
            "GET", "$base/<pid>/queue",
            SyncHandler { getQueue(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(PlayQueueResponse::class.java),
            "POST", "$base/<pid>/queue/<qid>",
            SyncHandler {
              playQueue(it.pathArgs().getValue("pid"), it.pathArgs().getValue("qid"))
            }),
        Route.with(
            em.serializerResponse(GenericResponse::class.java),
            "DELETE", "$base/<pid>/queue/<qid>",
            SyncHandler {
              removeFromOrClearQueue(it.pathArgs().getValue("pid"),
                  listOf(it.pathArgs().getValue("qid")))
            }),
        Route.with(
            em.serializerResponse(GenericResponse::class.java),
            "DELETE", "$base/<pid>/queue",
            SyncHandler {
              removeFromOrClearQueue(it.pathArgs().getValue("pid"),
                  it.request().parameters().getOrDefault("qid", emptyList()))
            }),
        Route.with(
            em.serializerResponse(SaveQueueResponse::class.java),
            "POST", "$base/<pid>/queue",
            SyncHandler { saveQueue(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(PlayNextResponse::class.java),
            "POST", "$base/<pid>/play/next",
            SyncHandler { playNext(it.pathArgs().getValue("pid")) }),
        Route.with(
            em.serializerResponse(PlayPreviousResponse::class.java),
            "POST", "$base/<pid>/play/previous",
            SyncHandler { playPrevious(it.pathArgs().getValue("pid")) })
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
          Try.of { PlayState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid state") })
        }
        .orElseThrow({ IllegalArgumentException("missing state") })

    heosClient.setPlayState(pid, state)
  }

  private fun getNowPlayingMedia(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getNowPlayingMedia(pid)
  }

  private fun getVolume(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getVolume(CommandGroup.PLAYER, pid)
  }

  private fun setVolume(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val level = rc.request().parameter("level")
        .map { level ->
          Try.of { level.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("level should be an integer") })
        }
        .orElseThrow({ IllegalArgumentException("missing level") })

    heosClient.setVolume(CommandGroup.PLAYER, pid, level)
  }

  private fun volumeUp(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val step = rc.request().parameter("step")
        .map { step ->
          Try.of { step.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("step should be an integer") })
        }
        .orElse(HeosClient.DEFAULT_VOLUME_UP_DOWN_STEP)
    heosClient.volumeUp(CommandGroup.PLAYER, pid, step)
  }

  private fun volumeDown(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val step = rc.request().parameter("step")
        .map { step ->
          Try.of { step.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("step should be an integer") })
        }
        .orElse(HeosClient.DEFAULT_VOLUME_UP_DOWN_STEP)
    heosClient.volumeDown(CommandGroup.PLAYER, pid, step)
  }

  private fun getMute(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getMute(CommandGroup.PLAYER, pid)
  }

  private fun setOrToggleMute(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val state = rc.request().parameter("state")
        .map { state ->
          Try.of { MuteState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid state") })
        }

    if (state.isPresent) {
      heosClient.setMute(CommandGroup.PLAYER, pid, state.get())
    } else {
      heosClient.toggleMute(CommandGroup.PLAYER, pid)
    }
  }

  private fun getPlayMode(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getPlayMode(pid)
  }

  private fun setPlayMode(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val repeat = rc.request().parameter("repeat")
        .map { state ->
          Try.of { PlayRepeatState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid repeat state") })
        }
    val shuffle = rc.request().parameter("shuffle")
        .map { state ->
          Try.of { PlayShuffleState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid shuffle state") })
        }

    if (!repeat.isPresent && !shuffle.isPresent) {
      throw IllegalArgumentException("missing both repeat and shuffle states")
    }

    if (repeat.isPresent && !shuffle.isPresent) {
      heosClient.setPlayMode(pid, repeat.get())
    } else if (!repeat.isPresent && shuffle.isPresent) {
      heosClient.setPlayMode(pid, shuffle.get())
    } else {
      heosClient.setPlayMode(pid, repeat.get(), shuffle.get())
    }
  }

  private fun getQueue(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val range = rc.request().parameter("range")
        .map { range ->
          Try.of {
            val (start, end) = range.split(",").map { it.trim().toInt() }
            IntRange(start, end)
          }.getOrElseThrow(Supplier {
            IllegalArgumentException("range should be of format `start,end`")
          })
        }
        .orElse(HeosClient.DEFAULT_RANGE)
    heosClient.getQueue(pid, range)
  }

  private fun playQueue(pid: String, qid: String)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.playQueue(pid, qid)
  }

  private fun removeFromOrClearQueue(pid: String, qids: List<String>)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    if (qids.isEmpty()) {
      heosClient.clearQueue(pid)
    } else {
      heosClient.removeFromQueue(pid, qids)
    }
  }

  private fun saveQueue(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val name = rc.request().parameter("name")
        .orElseThrow({ IllegalArgumentException("missing name") })
    heosClient.saveQueue(pid, name)
  }

  private fun playNext(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.playNext(pid)
  }

  private fun playPrevious(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.playPrevious(pid)
  }
}

class HeosGroupCommandResource(private val heosClient: HeosClient) {
  fun routes(): List<KRoute> {
    val base = "/groups"
    val em = EntityMiddleware.forCodec(JacksonEntityCodec.forMapper(JSON.mapper))

    val routes = listOf(
        Route.with(
            em.serializerResponse(GetGroupsResponse::class.java),
            "GET", base,
            SyncHandler { getGroups() }),
        Route.with(
            em.serializerResponse(GetGroupInfoResponse::class.java),
            "GET", "$base/<gid>",
            SyncHandler { getGroupInfo(it.pathArgs().getValue("gid")) }),
        Route.with(
            em.serializerResponse(SetGroupResponse::class.java),
            "POST", base,
            SyncHandler { upsertGroup(it) }),
        Route.with(
            em.serializerResponse(DeleteGroupResponse::class.java),
            "DELETE", base,
            SyncHandler { deleteGroup(it) }),
        Route.with(
            em.serializerResponse(GetVolumeResponse::class.java),
            "GET", "$base/<gid>/volume",
            SyncHandler { getVolume(it.pathArgs().getValue("gid")) }),
        Route.with(
            em.serializerResponse(SetVolumeResponse::class.java),
            "PATCH", "$base/<gid>/volume",
            SyncHandler { setVolume(it.pathArgs().getValue("gid"), it) }),
        Route.with(
            em.serializerResponse(VolumeUpResponse::class.java),
            "POST", "$base/<gid>/volume/up",
            SyncHandler { volumeUp(it.pathArgs().getValue("gid"), it) }),
        Route.with(
            em.serializerResponse(VolumeDownResponse::class.java),
            "POST", "$base/<gid>/volume/down",
            SyncHandler { volumeDown(it.pathArgs().getValue("gid"), it) }),
        Route.with(
            em.serializerResponse(GetMuteResponse::class.java),
            "GET", "$base/<gid>/mute",
            SyncHandler { getMute(it.pathArgs().getValue("gid")) }),
        Route.with(
            em.serializerResponse(GenericResponse::class.java),
            "PATCH", "$base/<gid>/mute",
            SyncHandler { setOrToggleMute(it.pathArgs().getValue("gid"), it) })
    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun getGroups() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getGroups()
  }

  private fun getGroupInfo(gid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getGroupInfo(gid)
  }

  private fun upsertGroup(rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val leaderId = rc.request().parameter("leader_id")
        .orElseThrow({ IllegalArgumentException("missing group leader") })
    val memberIds = rc.request().parameter("member_ids")
        .map { memberIds ->
          memberIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        .orElseThrow({ IllegalArgumentException("missing group member(s)") })
    heosClient.setGroup(leaderId, memberIds)
  }

  private fun deleteGroup(rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val leaderId = rc.request().parameter("leader_id")
        .orElseThrow({ IllegalArgumentException("missing group leader") })
    heosClient.deleteGroup(leaderId)
  }

  private fun getVolume(gid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getVolume(CommandGroup.GROUP, gid)
  }

  private fun setVolume(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val level = rc.request().parameter("level")
        .map { level ->
          Try.of { level.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("level should be an integer") })
        }
        .orElseThrow({ IllegalArgumentException("missing level") })

    heosClient.setVolume(CommandGroup.GROUP, pid, level)
  }

  private fun volumeUp(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val step = rc.request().parameter("step")
        .map { step ->
          Try.of { step.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("step should be an integer") })
        }
        .orElse(HeosClient.DEFAULT_VOLUME_UP_DOWN_STEP)
    heosClient.volumeUp(CommandGroup.GROUP, pid, step)
  }

  private fun volumeDown(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val step = rc.request().parameter("step")
        .map { step ->
          Try.of { step.toInt() }
              .getOrElseThrow(Supplier { IllegalArgumentException("step should be an integer") })
        }
        .orElse(HeosClient.DEFAULT_VOLUME_UP_DOWN_STEP)
    heosClient.volumeDown(CommandGroup.GROUP, pid, step)
  }

  private fun getMute(pid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getMute(CommandGroup.GROUP, pid)
  }

  private fun setOrToggleMute(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val state = rc.request().parameter("state")
        .map { state ->
          Try.of { MuteState.from(state) }
              .getOrElseThrow(Supplier { IllegalArgumentException("invalid state") })
        }

    if (state.isPresent) {
      heosClient.setMute(CommandGroup.GROUP, pid, state.get())
    } else {
      heosClient.toggleMute(CommandGroup.GROUP, pid)
    }
  }
}

class HeosBrowseCommandResource(private val heosClient: HeosClient) {
  fun routes(): List<KRoute> {
    val base = "/browse"
    val em = EntityMiddleware.forCodec(JacksonEntityCodec.forMapper(JSON.mapper))

    val routes = listOf(
        Route.with(
            em.serializerResponse(GetMusicSourcesResponse::class.java),
            "GET", "/music_sources",
            SyncHandler { getMusicSources() }),
        Route.with(
            em.serializerResponse(GetMusicSourceInfoResponse::class.java),
            "GET", "/music_sources/<sid>",
            SyncHandler { getMusicSourceInfo(it.pathArgs().getValue("sid")) }),
        Route.with(
            em.serializerResponse(BrowseMediaSourcesResponse::class.java),
            "GET", "$base/media_sources/<sid>",
            SyncHandler { browseMediaSources(it.pathArgs().getValue("sid"), it) }),
        Route.with(
            em.serializerResponse(BrowseTopMusicResponse::class.java),
            "GET", "$base/top_music/<sid>",
            SyncHandler { browseTopMusic(it.pathArgs().getValue("sid"), it) }),
        Route.with(
            em.serializerResponse(BrowseSourceContainersResponse::class.java),
            "GET", "$base/source_containers/<sid>/<cid>",
            SyncHandler {
              browseSourceContainers(it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("sid"), it)
            }),
        Route.with(
            em.serializerResponse(GetSearchCriteriaResponse::class.java),
            "GET", "/search_criteria/<sid>",
            SyncHandler { getSearchCriteria(it.pathArgs().getValue("sid")) }),
        Route.with(
            em.serializerResponse(SearchResponse::class.java),
            "GET", "/search/<sid>/<scid>",
            SyncHandler {
              search(it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("scid"),
                  it)
            }),
        Route.with(
            em.serializerResponse(PlayStreamResponse::class.java),
            "POST", "/players/<pid>/play/stream/<sid>/<cid>/<mid>",
            SyncHandler {
              playStream(it.pathArgs().getValue("pid"),
                  it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"),
                  it.pathArgs().getValue("mid"),
                  it)
            }),
        Route.with(
            em.serializerResponse(PlayInputResponse::class.java),
            "POST", "/players/<pid>/play/input",
            SyncHandler { playInput(it.pathArgs().getValue("pid"), it) }),
        Route.with(
            em.serializerResponse(AddToQueueResponse::class.java),
            "PUT", "/players/<pid>/queue/<sid>/<cid>",
            SyncHandler {
              addContainerToQueue(it.pathArgs().getValue("pid"),
                  it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"),
                  it)
            }),
        Route.with(
            em.serializerResponse(AddToQueueResponse::class.java),
            "PUT", "/players/<pid>/queue/<sid>/<cid>/<mid>",
            SyncHandler {
              addTrackToQueue(it.pathArgs().getValue("pid"),
                  it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"),
                  it.pathArgs().getValue("mid"),
                  it)
            }),
        Route.with(
            em.serializerResponse(RenamePlaylistResponse::class.java),
            "PUT", "/playlists/<sid>/<cid>",
            SyncHandler {
              renamePlaylist(it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"),
                  it)
            }),
        Route.with(
            em.serializerResponse(DeletePlaylistResponse::class.java),
            "DELETE", "/playlists/<sid>/<cid>",
            SyncHandler {
              deletePlaylist(it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"))
            }),
        Route.with(
            em.serializerResponse(RetrieveMetadataResponse::class.java),
            "GET", "/metadata/<sid>/<cid>",
            SyncHandler {
              retrieveMetadata(it.pathArgs().getValue("sid"),
                  it.pathArgs().getValue("cid"))
            }),
        Route.with(
            em.serializerResponse(GetServiceOptionsResponse::class.java),
            "GET", "/service_options",
            SyncHandler { getServiceOptions() }),
        Route.with(
            em.serializerResponse(SetServiceOptionResponse::class.java),
            "PATCH", "/service_options/<option_id>",
            SyncHandler {
              setServiceOption(it.pathArgs().getValue("option_id"), it)
            })
    ).map { r -> r.withMiddleware { Middleware.syncToAsync(it) } }

    return Api.prefixRoutes(routes, Api.Version.V0)
  }

  private fun getMusicSources() = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getMusicSources()
  }

  private fun getMusicSourceInfo(sid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getMusicSourceInfo(sid)
  }

  private fun browseMediaSources(sid: String, rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val range = rc.request().parameter("range")
        .map { range ->
          Try.of {
            val (start, end) = range.split(",").map { it.trim().toInt() }
            IntRange(start, end)
          }.getOrElseThrow(Supplier {
            IllegalArgumentException("range should be of format `start,end`")
          })
        }
        .orElse(HeosClient.DEFAULT_RANGE)
    heosClient.browseMediaSources(sid, range)
  }

  private fun browseTopMusic(sid: String, rc: RequestContext) = callAndBuildResponse({ heosClient.reconnect() }) {
    val range = rc.request().parameter("range")
        .map { range ->
          Try.of {
            val (start, end) = range.split(",").map { it.trim().toInt() }
            IntRange(start, end)
          }.getOrElseThrow(Supplier {
            IllegalArgumentException("range should be of format `start,end`")
          })
        }
        .orElse(HeosClient.DEFAULT_RANGE)
    heosClient.browseTopMusic(sid, range)
  }

  private fun browseSourceContainers(sid: String, cid: String, rc: RequestContext) =
      callAndBuildResponse({ heosClient.reconnect() }) {
        val range = rc.request().parameter("range")
            .map { range ->
              Try.of {
                val (start, end) = range.split(",").map { it.trim().toInt() }
                IntRange(start, end)
              }.getOrElseThrow(Supplier {
                IllegalArgumentException("range should be of format `start,end`")
              })
            }
            .orElse(HeosClient.DEFAULT_RANGE)
        heosClient.browseSourceContainers(sid, cid, range)
      }

  private fun getSearchCriteria(sid: String) = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getSearchCriteria(sid)
  }

  private fun search(sid: String, scid: String, rc: RequestContext) =
      callAndBuildResponse({ heosClient.reconnect() }) {
        val scidInt = Try.of {
          scid.toInt()
        }.getOrElseThrow(Supplier {
          IllegalArgumentException("scid should be an integer")
        })

        val searchString = rc.request().parameter("search_string")
            .orElseThrow { IllegalArgumentException("missing search_string") }

        val range = rc.request().parameter("range")
            .map { range ->
              Try.of {
                val (start, end) = range.split(",").map { it.trim().toInt() }
                IntRange(start, end)
              }.getOrElseThrow(Supplier {
                IllegalArgumentException("range should be of format `start,end`")
              })
            }
            .orElse(HeosClient.DEFAULT_RANGE)

        heosClient.search(sid, scidInt, searchString, range)
      }

  private fun playStream(pid: String, sid: String, cid: String, mid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val name = rc.request().parameter("name")
        .orElseThrow({ IllegalArgumentException("missing name") })
    heosClient.playStream(pid, sid, mid, name, if (cid == "_") HeosClient.DEFAULT_CID else cid)
  }

  private fun playInput(pid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    if (rc.request().parameters().isEmpty()) {
      throw IllegalArgumentException("neither mid, spid nor input is provided")
    }

    val mid = rc.request().parameter("mid").orElse(HeosClient.DEFAULT_MID)
    val spid = rc.request().parameter("spid").orElse(HeosClient.DEFAULT_SPID)
    val input = rc.request().parameter("input").orElse(HeosClient.DEFAULT_INPUT)
    heosClient.playInput(pid, mid, spid, input)
  }

  private fun getCriteriaId(rc: RequestContext) = rc.request().parameter("criteria_id")
      .map { criteriaId ->
        Try.of { AddCriteriaId.from(criteriaId.toInt()) }
            .filter { it != AddCriteriaId.UNKNOWN }
            .getOrElseThrow(Supplier { IllegalArgumentException("invalid criteria_id") })
      }
      .orElseThrow({ IllegalArgumentException("missing criteria_id") })

  private fun addContainerToQueue(pid: String, sid: String,
                                  cid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.addToQueue(pid, sid, cid, getCriteriaId(rc))
  }

  private fun addTrackToQueue(pid: String, sid: String,
                              cid: String, mid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.addToQueue(pid, sid, cid, getCriteriaId(rc), mid)
  }

  private fun renamePlaylist(sid: String, cid: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val name = rc.request().parameter("name")
        .orElseThrow { IllegalArgumentException("missing name") }
    heosClient.renamePlaylist(sid, cid, name)
  }

  private fun deletePlaylist(sid: String, cid: String)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.deletePlaylist(sid, cid)
  }

  private fun retrieveMetadata(sid: String, cid: String)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.retrieveMetadata(sid, cid)
  }

  private fun getServiceOptions()
      = callAndBuildResponse({ heosClient.reconnect() }) {
    heosClient.getServiceOptions()
  }

  private fun setServiceOption(optionId: String, rc: RequestContext)
      = callAndBuildResponse({ heosClient.reconnect() }) {
    val optionIdInt = Try.of {
      optionId.toInt()
    }.getOrElseThrow(Supplier {
      IllegalArgumentException("option_id should be an integer")
    })

    val range = rc.request().parameter("range")
        .map { range ->
          Try.of {
            val (start, end) = range.split(",").map { it.trim().toInt() }
            IntRange(start, end)
          }.getOrElseThrow(Supplier {
            IllegalArgumentException("range should be of format `start,end`")
          })
        }
        .orElse(HeosClient.DEFAULT_RANGE)

    heosClient.setServiceOption(Option(optionIdInt),
        AttributesBuilder()
            .add(rc.request().parameters().filter { it.key != "range" })
            .build(),
        range)
  }
}
