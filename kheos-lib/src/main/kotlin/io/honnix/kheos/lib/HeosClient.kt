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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.honnix.kheos.common.*
import io.honnix.kheos.common.Command.*
import io.honnix.kheos.common.CommandGroup.*
import org.slf4j.LoggerFactory
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

const val HEOS_PORT = 1255
const val COMMAND_DELIMITER = "\r\n"

internal fun mkCommand(command: GroupedCommand, attributes: Attributes): String {
  val attributesStr = if (attributes.isNotEmpty()) "?$attributes" else ""
  return "heos://${command.group.group}/${command.command}$attributesStr"
}

interface HeosClient : Closeable {
  companion object {
    fun newInstance(host: String): HeosClient = HeosClientImpl(host)

    val DEFAULT_HEARTBEAT_INITIAL_DELAY: Long = 0

    val DEFAULT_HEARTBEAT_INTERVAL: Long = 30

    val DEFAULT_VOLUME_UP_DOWN_STEP = 5

    val DEFAULT_RANGE = IntRange.EMPTY

    val DEFAULT_MID = ""

    val DEFAULT_SPID = ""

    val DEFAULT_INPUT = ""
  }

  fun reconnect(force: Boolean = false)

  fun startHeartbeat(initialDelay: Long = DEFAULT_HEARTBEAT_INITIAL_DELAY,
                     interval: Long = DEFAULT_HEARTBEAT_INTERVAL,
                     unit: TimeUnit = TimeUnit.SECONDS)

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

  fun volumeUp(commandGroup: CommandGroup, id: String,
               step: Int = DEFAULT_VOLUME_UP_DOWN_STEP): VolumeUpResponse

  fun volumeDown(commandGroup: CommandGroup, id: String,
                 step: Int = DEFAULT_VOLUME_UP_DOWN_STEP): VolumeDownResponse

  fun getMute(commandGroup: CommandGroup, id: String): GetMuteResponse

  fun setMute(commandGroup: CommandGroup, id: String, muteState: MuteState): SetMuteResponse

  fun toggleMute(commandGroup: CommandGroup, id: String): ToggleMuteResponse

  fun getPlayMode(pid: String): GetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState, shuffle: PlayShuffleState)
      : SetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState): SetPlayModeResponse

  fun setPlayMode(pid: String, shuffle: PlayShuffleState): SetPlayModeResponse

  fun getQueue(pid: String, range: IntRange = DEFAULT_RANGE): GetQueueResponse

  fun playQueue(pid: String, qid: String): PlayQueueResponse

  fun removeFromQueue(pid: String, qids: List<String>): RemoveFromQueueResponse

  fun saveQueue(pid: String, name: String): SaveQueueResponse

  fun clearQueue(pid: String): ClearQueueResponse

  fun playNext(pid: String): PlayNextResponse

  fun playPrevious(pid: String): PlayPreviousResponse

  fun getGroups(): GetGroupsResponse

  fun getGroupInfo(gid: String): GetGroupInfoResponse

  fun setGroup(leaderId: String, memberIds: List<String>): SetGroupResponse

  fun deleteGroup(leaderId: String): DeleteGroupResponse

  fun getMusicSources(): GetMusicSourcesResponse

  fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse

  fun browseMusicSources(sid: String, range: IntRange = DEFAULT_RANGE): BrowseMediaSourcesResponse

  fun browseTopMusic(sid: String, range: IntRange = DEFAULT_RANGE): BrowseTopMusicResponse

  fun browseSourceContainers(sid: String, cid: String, range: IntRange = DEFAULT_RANGE)
      : BrowseSourceContainersResponse

  fun getSearchCriteria(sid: String): GetSearchCriteriaResponse

  fun search(sid: String, search: String, scid: Int, range: IntRange = DEFAULT_RANGE): SearchResponse

  fun playStream(pid: String, sid: String, cid: String, mid: String, name: String): PlayStreamResponse

  fun playInput(pid: String, mid: String = DEFAULT_MID, spid: String = DEFAULT_SPID,
                input: String = DEFAULT_INPUT): PlayInputResponse

  fun addToQueue(pid: String, sid: String, cid: String,
                 aid: AddCriteriaId, mid: String = DEFAULT_MID): AddToQueueResponse

  fun renamePlaylist(sid: String, cid: String, name: String): RenamePlaylistResponse

  fun deletePlaylist(sid: String, cid: String): DeletePlaylistResponse

  fun retrieveMetadata(sid: String, cid: String): RetrieveMetadataResponse

  fun getServiceOptions(): GetServiceOptionsResponse

  fun setServiceOption(option: Option, attributes: Attributes, range: IntRange = DEFAULT_RANGE)
      : SetServiceOptionResponse
}

internal class HeosClientImpl(host: String,
                              private val socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                              private val heartbeatExecutorService: ScheduledExecutorService
                              = Executors.newSingleThreadScheduledExecutor())
  : HeosClient {
  companion object {
    private val logger = LoggerFactory.getLogger(HeosClientImpl::class.java)
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private data class HeosResponse(override val heos: Heos) : GenericResponse

  private var inErrorState = false

  private lateinit var socket: Socket

  @Synchronized
  override fun reconnect(force: Boolean) {
    if (inErrorState || force) {
      clientSocket().close()
      socket = socketFactory()
      inErrorState = false
    }
  }

  @Synchronized
  private fun clientSocket(): Socket {
    if (!this::socket.isInitialized) {
      socket = socketFactory()
    }
    return socket
  }

  private inline fun <reified T : GenericResponse> sendCommand(command: GroupedCommand,
                                                               attributes: Attributes = Attributes(mapOf())): T {
    val rawResponse = synchronized(this) {
      try {
        val output = PrintWriter(clientSocket().getOutputStream(), true)
        val input = BufferedReader(InputStreamReader(clientSocket().getInputStream()))

        val commandToSend = mkCommand(command, attributes)

        logger.debug("sending command $commandToSend")

        output.printf("$commandToSend$COMMAND_DELIMITER")
        input.readLine()
      } catch (e: IOException) {
        inErrorState = true
        val message = "failed to communicate with ${clientSocket().inetAddress}"
        logger.error(message)
        throw HeosClientException(message, e)
      }
    }

    inErrorState = false

    logger.debug(rawResponse)

    val heosResponse = JSON.mapper.readValue(rawResponse, HeosResponse::class.java)

    if (heosResponse.heos.result === Result.FAIL) {
      throw HeosCommandException.build(heosResponse.heos.message)
    }

    return JSON.mapper.readValue(rawResponse, T::class.java)
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

  override fun setPlayMode(pid: String, repeat: PlayRepeatState): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          AttributesBuilder()
              .add("pid", pid)
              .add("repeat", repeat)
              .build())

  override fun setPlayMode(pid: String, shuffle: PlayShuffleState): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          AttributesBuilder()
              .add("pid", pid)
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

  override fun setGroup(leaderId: String, memberIds: List<String>): SetGroupResponse {
    if (memberIds.isEmpty()) {
      throw IllegalArgumentException("at least one member should be specified")
    }

    return sendCommand(GroupedCommand(GROUP, SET_GROUP),
        AttributesBuilder()
            .add("pid", (listOf(leaderId) + memberIds).joinToString(","))
            .build())
  }

  override fun deleteGroup(leaderId: String): DeleteGroupResponse =
      sendCommand(GroupedCommand(GROUP, SET_GROUP),
          AttributesBuilder()
              .add("pid", leaderId)
              .build())

  override fun getMusicSources(): GetMusicSourcesResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES))

  override fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_SOURCE_INFO),
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

  override fun playInput(pid: String, mid: String, spid: String, input: String)
      : PlayInputResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT),
          AttributesBuilder()
              .add("pid", pid)
              .add("mid", mid::isNotEmpty, { mid })
              .add("spid", spid::isNotEmpty, { spid })
              .add("input", input::isNotEmpty, { input })
              .build())

  override fun addToQueue(pid: String, sid: String, cid: String, aid: AddCriteriaId, mid: String)
      : AddToQueueResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE),
          AttributesBuilder()
              .add("pid", pid)
              .add("sid", sid)
              .add("cid", cid)
              .add("mid", mid::isNotEmpty, { mid })
              .add("aid", aid.id)
              .build())

  override fun renamePlaylist(sid: String, cid: String, name: String): RenamePlaylistResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, RENAME_PLAYLIST),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .add("name", name)
              .build())

  override fun deletePlaylist(sid: String, cid: String): DeletePlaylistResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, DELETE_PLAYLIST),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .build())

  override fun retrieveMetadata(sid: String, cid: String): RetrieveMetadataResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, RETRIEVE_METADATA),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .build())

  override fun getServiceOptions(): GetServiceOptionsResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_SERVICE_OPTIONS))

  override fun setServiceOption(option: Option, attributes: Attributes, range: IntRange)
      : SetServiceOptionResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    if (option != Option.CREATE_NEW_STATION && !range.isEmpty()) {
      throw IllegalArgumentException("only ${Option.CREATE_NEW_STATION} supports range, $option given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, SET_SERVICE_OPTION),
        AttributesBuilder()
            .add("option", option.id)
            .add(attributes)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }
}

interface ChangeEventListener {
  fun onEvent(event: ChangeEvent)

  fun onException(exception: IOException) {

  }
}

interface HeosChangeEventsClient : Closeable {
  companion object {
    fun newInstance(host: String): HeosChangeEventsClient = HeosChangeEventsClientImpl(host)
  }

  fun start()

  fun register(listener: ChangeEventListener): Int

  fun unregister(id: Int)
}

internal class HeosChangeEventsClientImpl(host: String,
                                          private val socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                                          private val socketExecutorService: ExecutorService
                                          = Executors.newSingleThreadExecutor(),
                                          private val listenerExecutorService: ExecutorService
                                          = Executors.newFixedThreadPool(4))
  : HeosChangeEventsClient {
  companion object {
    private val logger = LoggerFactory.getLogger(HeosChangeEventsClientImpl::class.java)
  }

  private val id = AtomicInteger()

  private lateinit var clientSocket: Socket

  private val listeners = ConcurrentHashMap<Int, ChangeEventListener>()

  private fun registerForChangeEvents() {
    logger.info("register for change events")

    val output = PrintWriter(clientSocket.getOutputStream(), true)
    val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

    output.printf("${mkCommand(GroupedCommand(SYSTEM, REGISTER_FOR_CHANGE_EVENTS),
        AttributesBuilder()
            .add("enable", "on")
            .build())}$COMMAND_DELIMITER")

    val rawResponse = input.readLine()

    logger.debug(rawResponse)

    val response = JSON.mapper.readValue(rawResponse, RegisterForChangeEventsResponse::class.java)

    if (response.heos.result === Result.FAIL) {
      throw HeosCommandException.build(response.heos.message)
    }
  }

  override fun start() {
    clientSocket = socketFactory()

    registerForChangeEvents()

    socketExecutorService.execute {
      while (true) {
        val rawResponse = try {
          val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
          input.readLine()
        } catch (e: IOException) {
          if (e is SocketException && "Socket closed" == e.message) {
            break
          } else {
            val message = "failed to communicate with ${clientSocket.inetAddress}"
            logger.error(message)
            listeners.values.forEach {
              listenerExecutorService.execute {
                it.onException(e)
              }
            }
            continue
          }
        }

        logger.debug(rawResponse)

        val changeEvent = JSON.mapper.readValue(rawResponse, ChangeEventResponse::class.java).event

        listeners.values.forEach {
          listenerExecutorService.execute {
            it.onEvent(changeEvent)
          }
        }
      }
    }
  }

  override fun register(listener: ChangeEventListener): Int {
    val newId = id.getAndIncrement()
    listeners[newId] = listener
    return newId
  }

  override fun unregister(id: Int) {
    listeners - id
  }

  override fun close() {
    logger.info("closing connection to heos")
    clientSocket.close()
    socketExecutorService.shutdownNow()
    listenerExecutorService.shutdown()
  }
}
