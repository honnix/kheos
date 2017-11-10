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

  fun getVolume(pid: String): GetVolumeResponse

  fun setVolume(pid: String, level: Int): SetVolumeResponse

  fun volumeUp(pid: String, step: Int = 5): VolumeUpResponse

  fun volumeDown(pid: String, step: Int = 5): VolumeDownResponse

  fun getMute(pid: String): GetMuteResponse

  fun setMute(pid: String, state: PlayerMuteState): SetMuteResponse

  fun toggleMute(pid: String): ToggleMuteResponse

  fun getPlayMode(pid: String): GetPlayModeResponse

  fun setPlayMode(pid: String, repeat: PlayRepeatState, shuffle: PlayShuffleState): SetPlayModeResponse
}

internal class HeosClientImpl(host: String,
                              private val socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                              private val heartbeatExecutorService: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) : HeosClient {
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

  override fun getVolume(pid: String): GetVolumeResponse =
      sendCommand(GroupedCommand(PLAYER, GET_VOLUME),
          AttributesBuilder().add("pid", pid).build())

  override fun setVolume(pid: String, level: Int): SetVolumeResponse {
    if (level !in 0..100) {
      throw IllegalArgumentException("volume level should be in range [0, 100], $level given")
    }

    return sendCommand(GroupedCommand(PLAYER, SET_VOLUME),
        AttributesBuilder()
            .add("pid", pid)
            .add("level", level)
            .build())
  }

  override fun volumeUp(pid: String, step: Int): VolumeUpResponse {
    if (step !in 1..10) {
      throw IllegalArgumentException("volume step level should be in range [1, 10], $step given")
    }

    return sendCommand(GroupedCommand(PLAYER, VOLUME_UP),
        AttributesBuilder()
            .add("pid", pid)
            .add("step", step)
            .build())
  }

  override fun volumeDown(pid: String, step: Int): VolumeDownResponse {
    if (step !in 1..10) {
      throw IllegalArgumentException("volume step level should be in range [1, 10], $step given")
    }

    return sendCommand(GroupedCommand(PLAYER, VOLUME_DOWN
    ),
        AttributesBuilder()
            .add("pid", pid)
            .add("step", step)
            .build())
  }

  override fun getMute(pid: String): GetMuteResponse =
      sendCommand(GroupedCommand(PLAYER, GET_MUTE),
          AttributesBuilder().add("pid", pid).build())

  override fun setMute(pid: String, state: PlayerMuteState): SetMuteResponse =
      sendCommand(GroupedCommand(PLAYER, SET_MUTE),
          AttributesBuilder()
              .add("pid", pid)
              .add("state", state)
              .build())

  override fun toggleMute(pid: String): ToggleMuteResponse =
      sendCommand(GroupedCommand(PLAYER, TOGGLE_MUTE),
          AttributesBuilder().add("pid", pid).build())

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
}
