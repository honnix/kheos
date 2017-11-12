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
package io.honnix.kheos.lib

import io.honnix.kheos.lib.Command.*
import io.honnix.kheos.lib.CommandGroup.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val HEOS_PORT = 1255
const val COMMAND_DELIMITER = "\r\n"

interface HeosClient : Closeable {
  companion object {
    fun newInstance(host: String): HeosClient = HeosClientImpl(host)
  }

  fun reconnect()

  fun startHeartbeat(initialDelay: Long = 0, interval: Long = 30, unit: TimeUnit = TimeUnit.SECONDS)

  fun stopHeartbeat()

  fun heartbeat(): HeartbeatResponse

  fun checkAccount(): CheckAccountResponse

  fun signIn(userName: String, password: String): SignInResponse

  fun signOut(): SignOutResponse

  fun reboot(): RebootResponse

  fun getPlayers(): GetPlayersResponse

  fun getPlayerInfo(pid: String): GetPlayerInfoResponse

  fun getPlayState(pid: String): GetPlayStateResponse

  fun setPlayState(pid: String, state: PlayState): SetPlayStateResponse

  fun getNowPlayingMedia(pid: String): GetNowPlayingMediaResponse

  fun getVolume(commandGroup: CommandGroup, id: String): GetVolumeResponse

  fun setVolume(commandGroup: CommandGroup, id: String, level: Int): SetVolumeResponse

  fun volumeUp(commandGroup: CommandGroup, id: String, step: Int = 5): VolumeUpResponse

  fun volumeDown(commandGroup: CommandGroup, id: String, step: Int = 5): VolumeDownResponse

  fun getMute(commandGroup: CommandGroup, id: String): GetMuteResponse

  fun setMute(commandGroup: CommandGroup, id: String, muteState: MuteState): SetMuteResponse

  fun toggleMute(commandGroup: CommandGroup, id: String): ToggleMuteResponse

  fun getPlayMode(pid: String): GetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState, shuffle: PlayShuffleState)
      : SetPlayModeResponse

  fun getQueue(pid: String, range: IntRange = IntRange.EMPTY): GetQueueResponse

  fun playQueue(pid: String, qid: String): PlayQueueResponse

  fun removeFromQueue(pid: String, qids: List<String>): RemoveFromQueueResponse

  fun saveQueue(pid: String, name: String): SaveQueueResponse

  fun clearQueue(pid: String): ClearQueueResponse

  fun playNext(pid: String): PlayNextResponse

  fun playPrevious(pid: String): PlayPreviousResponse

  fun getGroups(): GetGroupsResponse

  fun getGroupInfo(gid: String): GetGroupInfoResponse

  fun setGroup(pids: List<String>): SetGroupResponse

  fun getMusicSources(): GetMusicSourcesResponse

  fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse

  fun browseMusicSources(sid: String, range: IntRange = IntRange.EMPTY): BrowseMediaSourcesResponse

  fun browseTopMusic(sid: String, range: IntRange = IntRange.EMPTY): BrowseTopMusicResponse

  fun browseSourceContainers(sid: String, cid: String, range: IntRange = IntRange.EMPTY)
      : BrowseSourceContainersResponse

  fun getSearchCriteria(sid: String): GetSearchCriteriaResponse

  fun search(sid: String, search: String, scid: Int, range: IntRange = IntRange.EMPTY): SearchResponse

  fun playStream(pid: String, sid: String, cid: String, mid: String, name: String): PlayStreamResponse

  fun playInput(pid: String, sid: String = "", mid: String = "", spid: String = "",
                input: String = ""): PlayInputResponse
}

internal class HeosClientImpl(host: String,
                              private val socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                              private val heartbeatExecutorService: ScheduledExecutorService
                              = Executors.newSingleThreadScheduledExecutor())
  : HeosClient {
  companion object {
    private val logger = LoggerFactory.getLogger(HeosClientImpl::class.java)
  }

  private lateinit var clientSocket: Socket

  @Synchronized
  override fun reconnect() {
    if (!clientSocket().isClosed) {
      clientSocket().close()
    }
    clientSocket = socketFactory()
  }

  @Synchronized
  private fun clientSocket() = try {
    clientSocket
  } catch (e: UninitializedPropertyAccessException) {
    clientSocket = socketFactory()
    clientSocket
  }

  private inline fun <reified T : GenericResponse> sendCommand(command: GroupedCommand,
                                                               attributes: Attributes = Attributes(mapOf())): T {
    val rawResponse = synchronized(this) {
      try {
        val output = PrintWriter(clientSocket().getOutputStream(), true)
        val input = BufferedReader(InputStreamReader(clientSocket().getInputStream()))

        output.printf("${mkCommand(command, attributes)}$COMMAND_DELIMITER")
        input.readLine()
      } catch (e: IOException) {
        val message = "failed to communicate with ${clientSocket().inetAddress}"
        logger.error(message)
        throw HeosClientException(message, e)
      }
    }

    logger.debug(rawResponse)

    val response = JSON.mapper.readValue(rawResponse, T::class.java)

    if (response.status.result === Result.FAIL) {
      throw HeosCommandException.build(response.status.message)
    }

    return response
  }

  private fun mkCommand(command: GroupedCommand, attributes: Attributes): String {
    val attributeStr = if (attributes.isNotEmpty()) "?$attributes" else ""
    return "heos://${command.group.group}/${command.command}$attributeStr"
  }

  override fun startHeartbeat(initialDelay: Long, interval: Long, unit: TimeUnit) {
    heartbeatExecutorService.scheduleWithFixedDelay({
      try {
        logger.info("sending heartbeat command")
        val response = heartbeat()
        logger.debug("received heartbeat response {}", response)
      } catch (e: HeosCommandException) {
        logger.warn("heartbeat command got a fail status: eid({}) text({})", e.eid, e.text, e)
      } catch (e: Exception) {
        logger.error("other failure", e)
      }
    }, initialDelay, interval, TimeUnit.SECONDS)
  }

  override fun stopHeartbeat() {
    logger.info("stopping heartbeat")
    heartbeatExecutorService.shutdownNow()
  }

  override fun close() {
    logger.info("closing connection to heos")
    clientSocket().close()
  }

  override fun heartbeat(): HeartbeatResponse =
      sendCommand(GroupedCommand(SYSTEM, HEART_BEAT))

  override fun checkAccount(): CheckAccountResponse =
      sendCommand(GroupedCommand(SYSTEM, CHECK_ACCOUNT))

  override fun signIn(userName: String, password: String): SignInResponse =
      sendCommand(GroupedCommand(SYSTEM, SIGN_IN),
          AttributesBuilder()
              .add("un", userName)
              .add("pw", password)
              .build())

  override fun signOut(): SignOutResponse =
      sendCommand(GroupedCommand(SYSTEM, SIGN_OUT))

  override fun reboot(): RebootResponse =
      sendCommand(GroupedCommand(SYSTEM, REBOOT))

  override fun getPlayers(): GetPlayersResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAYERS))

  override fun getPlayerInfo(pid: String): GetPlayerInfoResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAYER_INFO),
          AttributesBuilder().add("pid", pid).build())

  override fun getPlayState(pid: String): GetPlayStateResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAY_STATE),
          AttributesBuilder().add("pid", pid).build())

  override fun setPlayState(pid: String, state: PlayState): SetPlayStateResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_STATE),
          AttributesBuilder()
              .add("pid", pid)
              .add("state", state)
              .build())

  override fun getNowPlayingMedia(pid: String): GetNowPlayingMediaResponse =
      sendCommand(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA),
          AttributesBuilder().add("pid", pid).build())

  override fun getVolume(commandGroup: CommandGroup, id: String): GetVolumeResponse =
      sendCommand(GroupedCommand(commandGroup, GET_VOLUME),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun setVolume(commandGroup: CommandGroup, id: String, level: Int): SetVolumeResponse {
    if (level !in 0..100) {
      throw IllegalArgumentException("volume level should be in range [0, 100], $level given")
    }

    return sendCommand(GroupedCommand(commandGroup, SET_VOLUME),
        AttributesBuilder()
            .add("pid", { commandGroup === PLAYER }, { id })
            .add("gid", { commandGroup === GROUP }, { id })
            .add("level", level)
            .build())
  }

  override fun volumeUp(commandGroup: CommandGroup, id: String, step: Int): VolumeUpResponse {
    if (step !in 1..10) {
      throw IllegalArgumentException("volume step level should be in range [1, 10], $step given")
    }

    return sendCommand(GroupedCommand(commandGroup, VOLUME_UP),
        AttributesBuilder()
            .add("pid", { commandGroup === PLAYER }, { id })
            .add("gid", { commandGroup === GROUP }, { id })
            .add("step", step)
            .build())
  }

  override fun volumeDown(commandGroup: CommandGroup, id: String, step: Int): VolumeDownResponse {
    if (step !in 1..10) {
      throw IllegalArgumentException("volume step level should be in range [1, 10], $step given")
    }

    return sendCommand(GroupedCommand(commandGroup, VOLUME_DOWN),
        AttributesBuilder()
            .add("pid", { commandGroup === PLAYER }, { id })
            .add("gid", { commandGroup === GROUP }, { id })
            .add("step", step)
            .build())
  }

  override fun getMute(commandGroup: CommandGroup, id: String): GetMuteResponse =
      sendCommand(GroupedCommand(commandGroup, GET_MUTE),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun setMute(commandGroup: CommandGroup, id: String, muteState: MuteState): SetMuteResponse =
      sendCommand(GroupedCommand(commandGroup, SET_MUTE),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .add("state", muteState)
              .build())

  override fun toggleMute(commandGroup: CommandGroup, id: String): ToggleMuteResponse =
      sendCommand(GroupedCommand(commandGroup, TOGGLE_MUTE),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun getPlayMode(pid: String): GetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAY_MODE),
          AttributesBuilder().add("pid", pid).build())

  override fun setPlayMode(pid: String, repeat: PlayRepeatState,
                           shuffle: PlayShuffleState): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          AttributesBuilder()
              .add("pid", pid)
              .add("repeat", repeat)
              .add("shuffle", shuffle)
              .build())

  override fun getQueue(pid: String, range: IntRange): GetQueueResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(PLAYER, GET_QUEUE),
        AttributesBuilder()
            .add("pid", pid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun playQueue(pid: String, qid: String): PlayQueueResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_QUEUE),
          AttributesBuilder()
              .add("pid", pid)
              .add("qid", qid)
              .build())

  override fun removeFromQueue(pid: String, qids: List<String>): RemoveFromQueueResponse {
    if (qids.isEmpty()) {
      throw IllegalArgumentException("at least one qid should be specified")
    }

    return sendCommand(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
        AttributesBuilder()
            .add("pid", pid)
            .add("qid", qids.joinToString(","))
            .build())
  }

  override fun saveQueue(pid: String, name: String): SaveQueueResponse =
      sendCommand(GroupedCommand(PLAYER, SAVE_QUEUE),
          AttributesBuilder()
              .add("pid", pid)
              .add("name", name)
              .build())

  override fun clearQueue(pid: String): ClearQueueResponse =
      sendCommand(GroupedCommand(PLAYER, CLEAR_QUEUE),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun playNext(pid: String): PlayNextResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_NEXT),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun playPrevious(pid: String): PlayPreviousResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_PREVIOUS),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun getGroups(): GetGroupsResponse =
      sendCommand(GroupedCommand(GROUP, GET_GROUPS))

  override fun getGroupInfo(gid: String): GetGroupInfoResponse =
      sendCommand(GroupedCommand(GROUP, GET_GROUP_INFO),
          AttributesBuilder()
              .add("gid", gid)
              .build())

  override fun setGroup(pids: List<String>): SetGroupResponse {
    if (pids.isEmpty()) {
      throw IllegalArgumentException("at least one pid should be specified")
    }

    return sendCommand(GroupedCommand(GROUP, SET_GROUP),
        AttributesBuilder()
            .add("pid", pids.joinToString(","))
            .build())
  }

  override fun getMusicSources(): GetMusicSourcesResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES))

  override fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCE_INFO),
          AttributesBuilder()
              .add("sid", sid)
              .build())

  override fun browseMusicSources(sid: String, range: IntRange): BrowseMediaSourcesResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
        AttributesBuilder()
            .add("sid", sid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun browseTopMusic(sid: String, range: IntRange): BrowseTopMusicResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
        AttributesBuilder()
            .add("sid", sid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun browseSourceContainers(sid: String, cid: String, range: IntRange)
      : BrowseSourceContainersResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
        AttributesBuilder()
            .add("sid", sid)
            .add("cid", cid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun getSearchCriteria(sid: String): GetSearchCriteriaResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_SEARCH_CRITERIA),
          AttributesBuilder()
              .add("sid", sid)
              .build())

  override fun search(sid: String, search: String, scid: Int, range: IntRange): SearchResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, SEARCH),
        AttributesBuilder()
            .add("sid", sid)
            .add("search", search)
            .add("scid", scid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun playStream(pid: String, sid: String, cid: String, mid: String, name: String)
      : PlayStreamResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM),
          AttributesBuilder()
              .add("pid", pid)
              .add("sid", sid)
              .add("cid", cid)
              .add("mid", mid)
              .add("name", name)
              .build())

  override fun playInput(pid: String, sid: String, mid: String, spid: String, input: String)
      : PlayInputResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT),
          AttributesBuilder()
              .add("pid", pid)
              .add("sid", sid::isNotEmpty, { sid })
              .add("mid", mid::isNotEmpty, { mid })
              .add("spid", spid::isNotEmpty, { spid })
              .add("input", input::isNotEmpty, { input })
              .build())
}
