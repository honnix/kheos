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

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.honnix.kheos.common.*
import io.honnix.kheos.common.Command.*
import io.honnix.kheos.common.CommandGroup.*
import io.honnix.kheos.proto.base.v1.*
import io.honnix.kheos.proto.base.v1.Result.fail
import io.honnix.kheos.proto.browse.v1.*
import io.honnix.kheos.proto.event.v1.ChangeEventResponse
import io.honnix.kheos.proto.event.v1.RegisterForChangeEventsResponse
import io.honnix.kheos.proto.group.v1.DeleteGroupResponse
import io.honnix.kheos.proto.group.v1.GetGroupInfoResponse
import io.honnix.kheos.proto.group.v1.GetGroupsResponse
import io.honnix.kheos.proto.group.v1.SetGroupResponse
import io.honnix.kheos.proto.player.v1.*
import io.honnix.kheos.proto.system.v1.*
import org.slf4j.LoggerFactory
import java.io.*
import java.net.Socket
import java.net.SocketException
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

    const val DEFAULT_HEARTBEAT_INITIAL_DELAY: Long = 0

    const val DEFAULT_HEARTBEAT_INTERVAL: Long = 30

    const val DEFAULT_VOLUME_UP_DOWN_STEP = 5

    val DEFAULT_RANGE = IntRange.EMPTY

    const val DEFAULT_CID = ""

    const val DEFAULT_MID = ""

    const val DEFAULT_SPID = ""

    const val DEFAULT_INPUT = ""
  }

  fun connect()

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

  fun setPlayState(pid: String, state: PlayState.State): SetPlayStateResponse

  fun getNowPlayingMedia(pid: String): GetNowPlayingMediaResponse

  fun getVolume(commandGroup: CommandGroup, id: String): GetVolumeResponse

  fun setVolume(commandGroup: CommandGroup, id: String, level: Int): SetVolumeResponse

  fun volumeUp(commandGroup: CommandGroup, id: String,
               step: Int = DEFAULT_VOLUME_UP_DOWN_STEP): VolumeUpResponse

  fun volumeDown(commandGroup: CommandGroup, id: String,
                 step: Int = DEFAULT_VOLUME_UP_DOWN_STEP): VolumeDownResponse

  fun getMute(commandGroup: CommandGroup, id: String): GetMuteResponse

  fun setMute(commandGroup: CommandGroup, id: String, muteState: MuteState.State): SetMuteResponse

  fun toggleMute(commandGroup: CommandGroup, id: String): ToggleMuteResponse

  fun getPlayMode(pid: String): GetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState.State, shuffle: PlayShuffleState.State)
      : SetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState.State): SetPlayModeResponse

  fun setPlayMode(pid: String, shuffle: PlayShuffleState.State): SetPlayModeResponse

  fun getQueue(pid: String, range: IntRange = DEFAULT_RANGE): GetQueueResponse

  fun playQueue(pid: String, qid: String): PlayQueueResponse

  fun removeFromQueue(pid: String, qids: List<String>): RemoveFromQueueResponse

  fun saveQueue(pid: String, name: String): SaveQueueResponse

  fun clearQueue(pid: String): ClearQueueResponse

  fun playPrevious(pid: String): PlayPreviousResponse

  fun playNext(pid: String): PlayNextResponse

  fun getGroups(): GetGroupsResponse

  fun getGroupInfo(gid: String): GetGroupInfoResponse

  fun setGroup(leaderId: String, memberIds: List<String>): SetGroupResponse

  fun deleteGroup(leaderId: String): DeleteGroupResponse

  fun getMusicSources(): GetMusicSourcesResponse

  fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse

  fun browseMediaSources(sid: String, range: IntRange = DEFAULT_RANGE): BrowseMediaSourcesResponse

  fun browseTopMusic(sid: String, range: IntRange = DEFAULT_RANGE): BrowseTopMusicResponse

  fun browseSourceContainers(sid: String, cid: String, range: IntRange = DEFAULT_RANGE)
      : BrowseSourceContainersResponse

  fun getSearchCriteria(sid: String): GetSearchCriteriaResponse

  fun search(sid: String, scid: Scid, search: String, range: IntRange = DEFAULT_RANGE): SearchResponse

  fun playStream(pid: String, sid: String, mid: String, name: String, cid: String = DEFAULT_CID)
      : PlayStreamResponse

  fun playInput(pid: String, mid: String = DEFAULT_MID, spid: String = DEFAULT_SPID,
                input: String = DEFAULT_INPUT): PlayInputResponse

  fun addToQueue(pid: String, sid: String, cid: String,
                 aid: AddToQueueRequest.AddCriteriaId,
                 mid: String = DEFAULT_MID): AddToQueueResponse

  fun renamePlaylist(sid: String, cid: String, name: String): RenamePlaylistResponse

  fun deletePlaylist(sid: String, cid: String): DeletePlaylistResponse

  fun retrieveMetadata(sid: String, cid: String): RetrieveMetadataResponse

  fun setServiceOption(option: OptionId, attributes: Attributes, range: IntRange = DEFAULT_RANGE)
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

  private var inErrorState = false

  private lateinit var clientSocket: Socket

  @Synchronized
  override fun connect() {
    clientSocket = socketFactory()
  }

  @Synchronized
  override fun reconnect(force: Boolean) {
    if (inErrorState || force) {
      clientSocket.close()
      clientSocket = socketFactory()
      inErrorState = false
    }
  }

  private inline fun <reified T : Message> sendCommand(command: GroupedCommand,
                                                       messagePrototype: T,
                                                       attributes: Attributes = Attributes(mapOf())): T {
    val rawResponse = synchronized(this) {
      try {
        val output = PrintWriter(clientSocket.getOutputStream(), true)
        val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

        val commandToSend = mkCommand(command, attributes)

        logger.debug("sending command $commandToSend")

        // printf flushes
        output.printf("$commandToSend$COMMAND_DELIMITER")

        val line = input.readLine()
        if (line.contains("command under process", true)) {
          logger.debug(line)
          // block until command processed
          input.readLine()
        } else {
          line
        }
      } catch (e: IOException) {
        inErrorState = true
        val message = "failed to communicate with ${clientSocket.inetAddress}"
        logger.error(message)
        throw HeosClientException(message, e)
      }
    }

    inErrorState = false

    logger.debug(rawResponse)

    val heosResponseBuilder = HeosResponse.newBuilder()
    JsonFormat.parser().ignoringUnknownFields().merge(rawResponse, heosResponseBuilder)
    val heosResponse = heosResponseBuilder.build()

    if (heosResponse.heos.result === fail) {
      throw HeosCommandException.build(heosResponse.heos.message)
    }

    val builder = messagePrototype.toBuilder()
    JsonFormat.parser().merge(rawResponse, builder)
    val message = builder.build()

    return when (message) {
      is T -> message
      else -> throw IllegalArgumentException("corrupted builder of message ${T::class}")
    }
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
    clientSocket.close()
  }

  override fun heartbeat(): HeartbeatResponse =
      sendCommand(GroupedCommand(SYSTEM, HEART_BEAT), HeartbeatResponse.getDefaultInstance())

  override fun checkAccount(): CheckAccountResponse =
      sendCommand(GroupedCommand(SYSTEM, CHECK_ACCOUNT), CheckAccountResponse.getDefaultInstance())

  override fun signIn(userName: String, password: String): SignInResponse =
      sendCommand(GroupedCommand(SYSTEM, SIGN_IN),
          SignInResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("un", userName)
              .add("pw", password)
              .build())

  override fun signOut(): SignOutResponse =
      sendCommand(GroupedCommand(SYSTEM, SIGN_OUT), SignOutResponse.getDefaultInstance())

  override fun reboot(): RebootResponse =
      sendCommand(GroupedCommand(SYSTEM, REBOOT), RebootResponse.getDefaultInstance())

  override fun getPlayers(): GetPlayersResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAYERS), GetPlayersResponse.getDefaultInstance())

  override fun getPlayerInfo(pid: String): GetPlayerInfoResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAYER_INFO),
          GetPlayerInfoResponse.getDefaultInstance(),
          AttributesBuilder().add("pid", pid).build())

  override fun getPlayState(pid: String): GetPlayStateResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAY_STATE),
          GetPlayStateResponse.getDefaultInstance(),
          AttributesBuilder().add("pid", pid).build())

  override fun setPlayState(pid: String, state: PlayState.State): SetPlayStateResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_STATE),
          SetPlayStateResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("state", state.name.toLowerCase())
              .build())

  override fun getNowPlayingMedia(pid: String): GetNowPlayingMediaResponse =
      sendCommand(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA),
          GetNowPlayingMediaResponse.getDefaultInstance(),
          AttributesBuilder().add("pid", pid).build())

  override fun getVolume(commandGroup: CommandGroup, id: String): GetVolumeResponse =
      sendCommand(GroupedCommand(commandGroup, GET_VOLUME),
          GetVolumeResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun setVolume(commandGroup: CommandGroup, id: String, level: Int): SetVolumeResponse {
    if (level !in 0..100) {
      throw IllegalArgumentException("volume level should be in range [0, 100], $level given")
    }

    return sendCommand(GroupedCommand(commandGroup, SET_VOLUME),
        SetVolumeResponse.getDefaultInstance(),
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
        VolumeUpResponse.getDefaultInstance(),
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
        VolumeDownResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("pid", { commandGroup === PLAYER }, { id })
            .add("gid", { commandGroup === GROUP }, { id })
            .add("step", step)
            .build())
  }

  override fun getMute(commandGroup: CommandGroup, id: String): GetMuteResponse =
      sendCommand(GroupedCommand(commandGroup, GET_MUTE),
          GetMuteResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun setMute(commandGroup: CommandGroup, id: String, muteState: MuteState.State): SetMuteResponse =
      sendCommand(GroupedCommand(commandGroup, SET_MUTE),
          SetMuteResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .add("state", muteState.name.toLowerCase())
              .build())

  override fun toggleMute(commandGroup: CommandGroup, id: String): ToggleMuteResponse =
      sendCommand(GroupedCommand(commandGroup, TOGGLE_MUTE),
          ToggleMuteResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", { commandGroup === PLAYER }, { id })
              .add("gid", { commandGroup === GROUP }, { id })
              .build())

  override fun getPlayMode(pid: String): GetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, GET_PLAY_MODE),
          GetPlayModeResponse.getDefaultInstance(),
          AttributesBuilder().add("pid", pid).build())

  override fun setPlayMode(pid: String, repeat: PlayRepeatState.State,
                           shuffle: PlayShuffleState.State): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          SetPlayModeResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("repeat", repeat.name.toLowerCase())
              .add("shuffle", shuffle.name.toLowerCase())
              .build())

  override fun setPlayMode(pid: String, repeat: PlayRepeatState.State): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          SetPlayModeResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("repeat", repeat.name.toLowerCase())
              .build())

  override fun setPlayMode(pid: String, shuffle: PlayShuffleState.State): SetPlayModeResponse =
      sendCommand(GroupedCommand(PLAYER, SET_PLAY_MODE),
          SetPlayModeResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("shuffle", shuffle.name.toLowerCase())
              .build())

  override fun getQueue(pid: String, range: IntRange): GetQueueResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(PLAYER, GET_QUEUE),
        GetQueueResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("pid", pid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun playQueue(pid: String, qid: String): PlayQueueResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_QUEUE),
          PlayQueueResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("qid", qid)
              .build())

  override fun removeFromQueue(pid: String, qids: List<String>): RemoveFromQueueResponse {
    if (qids.isEmpty()) {
      throw IllegalArgumentException("at least one qid should be specified")
    }

    return sendCommand(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
        RemoveFromQueueResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("pid", pid)
            .add("qid", qids.joinToString(","))
            .build())
  }

  override fun saveQueue(pid: String, name: String): SaveQueueResponse =
      sendCommand(GroupedCommand(PLAYER, SAVE_QUEUE),
          SaveQueueResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("name", name)
              .build())

  override fun clearQueue(pid: String): ClearQueueResponse =
      sendCommand(GroupedCommand(PLAYER, CLEAR_QUEUE),
          ClearQueueResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun playPrevious(pid: String): PlayPreviousResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_PREVIOUS),
          PlayPreviousResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun playNext(pid: String): PlayNextResponse =
      sendCommand(GroupedCommand(PLAYER, PLAY_NEXT),
          PlayNextResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .build())

  override fun getGroups(): GetGroupsResponse =
      sendCommand(GroupedCommand(GROUP, GET_GROUPS), GetGroupsResponse.getDefaultInstance())

  override fun getGroupInfo(gid: String): GetGroupInfoResponse =
      sendCommand(GroupedCommand(GROUP, GET_GROUP_INFO),
          GetGroupInfoResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("gid", gid)
              .build())

  override fun setGroup(leaderId: String, memberIds: List<String>): SetGroupResponse {
    if (memberIds.isEmpty()) {
      throw IllegalArgumentException("at least one member should be specified")
    }

    return sendCommand(GroupedCommand(GROUP, SET_GROUP),
        SetGroupResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("pid", (listOf(leaderId) + memberIds).joinToString(","))
            .build())
  }

  override fun deleteGroup(leaderId: String): DeleteGroupResponse =
      sendCommand(GroupedCommand(GROUP, SET_GROUP),
          DeleteGroupResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", leaderId)
              .build())

  override fun getMusicSources(): GetMusicSourcesResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES),
          GetMusicSourcesResponse.getDefaultInstance())

  override fun getMusicSourceInfo(sid: String): GetMusicSourceInfoResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_SOURCE_INFO),
          GetMusicSourceInfoResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("sid", sid)
              .build())

  override fun browseMediaSources(sid: String, range: IntRange): BrowseMediaSourcesResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
        BrowseMediaSourcesResponse.getDefaultInstance(),
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
        BrowseTopMusicResponse.getDefaultInstance(),
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
        BrowseSourceContainersResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("sid", sid)
            .add("cid", cid)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun getSearchCriteria(sid: String): GetSearchCriteriaResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, GET_SEARCH_CRITERIA),
          GetSearchCriteriaResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("sid", sid)
              .build())

  override fun search(sid: String, scid: Scid, search: String, range: IntRange): SearchResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, SEARCH),
        SearchResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("sid", sid)
            .add("search", search)
            .add("scid", scid.number)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }

  override fun playStream(pid: String, sid: String, mid: String, name: String, cid: String)
      : PlayStreamResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM),
          PlayStreamResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("sid", sid)
              .add("cid", cid::isNotEmpty, { cid })
              .add("mid", mid)
              .add("name", name)
              .build())

  override fun playInput(pid: String, mid: String, spid: String, input: String)
      : PlayInputResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT),
          PlayInputResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("mid", mid::isNotEmpty, { mid })
              .add("spid", spid::isNotEmpty, { spid })
              .add("input", input::isNotEmpty, { input })
              .build())

  override fun addToQueue(pid: String, sid: String, cid: String,
                          aid: AddToQueueRequest.AddCriteriaId, mid: String)
      : AddToQueueResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE),
          AddToQueueResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("pid", pid)
              .add("sid", sid)
              .add("cid", cid)
              .add("mid", mid::isNotEmpty, { mid })
              .add("aid", aid.number)
              .build())

  override fun renamePlaylist(sid: String, cid: String, name: String): RenamePlaylistResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, RENAME_PLAYLIST),
          RenamePlaylistResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .add("name", name)
              .build())

  override fun deletePlaylist(sid: String, cid: String): DeletePlaylistResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, DELETE_PLAYLIST),
          DeletePlaylistResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .build())

  override fun retrieveMetadata(sid: String, cid: String): RetrieveMetadataResponse =
      sendCommand(GroupedCommand(CommandGroup.BROWSE, RETRIEVE_METADATA),
          RetrieveMetadataResponse.getDefaultInstance(),
          AttributesBuilder()
              .add("sid", sid)
              .add("cid", cid)
              .build())

  override fun setServiceOption(option: OptionId, attributes: Attributes, range: IntRange)
      : SetServiceOptionResponse {
    if (range.start < 0) {
      throw IllegalArgumentException("range starts from 0, $range given")
    }

    if (option != OptionId.CREATE_NEW_STATION && !range.isEmpty()) {
      throw IllegalArgumentException("only ${OptionId.CREATE_NEW_STATION} supports range, $option given")
    }

    return sendCommand(GroupedCommand(CommandGroup.BROWSE, SET_SERVICE_OPTION),
        SetServiceOptionResponse.getDefaultInstance(),
        AttributesBuilder()
            .add("option", option.number)
            .add(attributes)
            .add("range", { !range.isEmpty() }, { "${range.start},${range.endInclusive}" })
            .build())
  }
}

interface ChangeEventListener {
  fun onEvent(event: ChangeEventResponse.ChangeEvent)

  fun onException(exception: IOException) {}
}

interface HeosChangeEventsClient : Closeable {
  companion object {
    fun newInstance(host: String): HeosChangeEventsClient = HeosChangeEventsClientImpl(host)
  }

  fun connect()

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
            .add("enable", RegisterChangeEvents.ON)
            .build())}$COMMAND_DELIMITER")

    val rawResponse = input.readLine()

    logger.debug(rawResponse)

    val responseBuilder = RegisterForChangeEventsResponse.newBuilder()
    JsonFormat.parser().ignoringUnknownFields().merge(rawResponse, responseBuilder)
    val response = responseBuilder.build()

    if (response.heos.result === fail) {
      throw HeosCommandException.build(response.heos.message)
    }
  }

  override fun connect() {
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

        val responseBuilder = ChangeEventResponse.newBuilder()
        JsonFormat.parser().ignoringUnknownFields().merge(rawResponse, responseBuilder)
        val changeEvent = responseBuilder.build().heos

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
