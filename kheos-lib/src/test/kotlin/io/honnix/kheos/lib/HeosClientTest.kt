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
import io.honnix.kheos.lib.Control.NETWORK
import io.honnix.kheos.lib.MediaType.STATION
import io.honnix.kheos.lib.PlayState.PLAY
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.jmock.lib.concurrent.DeterministicScheduler
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.TimeUnit

class HeosClientImplTest : StringSpec() {
  private class QuietDeterministicScheduler : DeterministicScheduler() {
    private var isShutdown = false

    override fun shutdownNow(): MutableList<Runnable> {
      isShutdown = true
      return mutableListOf()
    }

    override fun isShutdown() = isShutdown
  }

  private val scheduler = QuietDeterministicScheduler()
  private val socket = mock<Socket>()
  private val heosClient = HeosClientImpl("localhost", { socket }, scheduler)

  private fun prepareInputOutput(response: GenericResponse): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
    val input = ByteArrayInputStream(JSON.serialize(response))
    `when`(socket.getInputStream()).thenReturn(input)

    val output = ByteArrayOutputStream()
    `when`(socket.getOutputStream()).thenReturn(output)

    return Pair(input, output)
  }

  init {
    "should create an instance of HeosClientImpl" {
      HeosClient.newInstance("localhost")::class shouldBe HeosClientImpl::class
    }

    "should reconnect" {
      `when`(socket.isClosed).thenReturn(false)
      heosClient.reconnect()
      verify(socket).isClosed
      verify(socket).close()

      reset(socket)

      `when`(socket.isClosed).thenReturn(true)
      heosClient.reconnect()
      verify(socket).isClosed
      verify(socket, never()).close()
    }
    
    "should close" {
      heosClient.close()
      verify(socket).close()
    }

    "should schedule heartbeat" {
      val response = HeartbeatResponse(
          Status(GroupedCommand(SYSTEM, HEART_BEAT),
              Result.SUCCESS, Message(mapOf())))

      val (input, output) = prepareInputOutput(response)

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/heart_beat$COMMAND_DELIMITER"

      heosClient.stopHeartbeat()
      scheduler.isShutdown shouldBe true
    }

    "should fail heartbeat" {
      val response = HeartbeatResponse(
          Status(GroupedCommand(SYSTEM, HEART_BEAT),
              Result.FAIL, Message.Builder()
              .add("eid", ErrorId.INTERNAL_ERROR.eid)
              .add("text", "System Internal Error")
              .build()))

      val (input, output) = prepareInputOutput(response)

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/heart_beat$COMMAND_DELIMITER"

      heosClient.stopHeartbeat()
      scheduler.isShutdown shouldBe true
    }

    "should fail heartbeat badly" {
      val output = mock<ByteArrayOutputStream>()
      `when`(socket.getOutputStream()).thenReturn(output)
      doThrow(RuntimeException()).`when`(output).write(any<ByteArray>(), anyInt(), anyInt())

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)
    }

    "should check account" {
      val expectedResponse = CheckAccountResponse(
          Status(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
              Result.SUCCESS, Message()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.checkAccount()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account$COMMAND_DELIMITER"
    }

    "should fail to check account" {
      val response = CheckAccountResponse(
          Status(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
              Result.FAIL, Message.Builder()
              .add("eid", ErrorId.INTERNAL_ERROR.eid)
              .add("text", "System Internal Error")
              .build()))

      val (input, output) = prepareInputOutput(response)

      val exception = shouldThrow<HeosCommandException> {
        heosClient.checkAccount()
      }
      exception.eid shouldBe ErrorId.INTERNAL_ERROR
      exception.text shouldBe "System Internal Error"

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account$COMMAND_DELIMITER"
    }

    "should fail to check account due to broken socket" {
      `when`(socket.getOutputStream()).thenThrow(SocketException())

      val exception = shouldThrow<HeosClientException> {
        heosClient.checkAccount()
      }
    }

    "should sign in" {
      val expectedResponse = SignInResponse(
          Status(GroupedCommand(SYSTEM, SIGN_IN),
              Result.SUCCESS, Message.Builder()
              .add("signed_in")
              .add("un", "user@example.com")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.signIn("user@example.com", "bar")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/sign_in?un=user@example.com&pw=bar$COMMAND_DELIMITER"
    }

    "should sign out" {
      val expectedResponse = SignOutResponse(
          Status(GroupedCommand(SYSTEM, SIGN_OUT),
              Result.SUCCESS, Message.Builder()
              .add("signed_out")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.signOut()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/sign_out$COMMAND_DELIMITER"
    }

    "should reboot" {
      val expectedResponse = RebootResponse(
          Status(GroupedCommand(SYSTEM, REBOOT),
              Result.SUCCESS, Message()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.reboot()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/reboot$COMMAND_DELIMITER"
    }

    "should get all players" {
      val expectedResponse = GetPlayersResponse(
          Status(GroupedCommand(PLAYER, GET_PLAYERS),
              Result.SUCCESS, Message()),
          listOf(
              Player("name0", "0", "model0",
                  "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE),
              Player("name1", "1", "model1",
                  "0.1", "192.168.1.101", "wifi", Lineout.FIXED,
                  "100", NETWORK)))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayers()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_players$COMMAND_DELIMITER"
    }

    "should get player info" {
      val expectedResponse = GetPlayerInfoResponse(
          Status(GroupedCommand(PLAYER, GET_PLAYER_INFO),
              Result.SUCCESS, Message()),
          Player("name0", "0", "model0",
              "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayerInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_player_info?pid=0$COMMAND_DELIMITER"
    }

    "should get play state" {
      val expectedResponse = GetPlayStateResponse(
          Status(GroupedCommand(PLAYER, GET_PLAY_STATE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("state", "play")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayState("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_play_state?pid=0$COMMAND_DELIMITER"
    }

    "should set play state" {
      val expectedResponse = SetPlayStateResponse(
          Status(GroupedCommand(PLAYER, SET_PLAY_STATE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("state", "play")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayState("0", PLAY)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_state?pid=0&state=play$COMMAND_DELIMITER"
    }

    "should get now playing meida" {
      val expectedResponse = GetNowPlayingMediaResponse(
          Status(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()),
          Media(STATION, "song", "album", "artist",
              URL("http://example.com"), "0", "0", "0", "0",
              station = "station"),
          listOf(mapOf("play" to
              listOf(mapOf("id" to "19", "name" to "Add to HEOS Favorites")))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getNowPlayingMedia("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_now_playing_media?pid=0$COMMAND_DELIMITER"
    }

    "should get volume" {
      val expectedResponse = GetVolumeResponse(
          Status(GroupedCommand(PLAYER, GET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("level", "10")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getVolume("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_volume?pid=0$COMMAND_DELIMITER"
    }

    "should set volume" {
      val expectedResponse = SetVolumeResponse(
          Status(GroupedCommand(PLAYER, SET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("level", "10")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setVolume("0", 10)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_volume?pid=0&level=10$COMMAND_DELIMITER"
    }

    "should throw if volume level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setVolume("0", -1)
      }
    }

    "should volume up" {
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(PLAYER, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp("0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_up?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should volume up with default step" {
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(PLAYER, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "5")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_up?pid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if volume up step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeUp("0", -1)
      }
    }

    "should volume down" {
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(PLAYER, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown("0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_down?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should volume down with default step" {
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(PLAYER, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "5")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_down?pid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if volume down step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeDown("0", 11)
      }
    }

    "should get mute" {
      val expectedResponse = GetMuteResponse(
          Status(GroupedCommand(PLAYER, GET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMute("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_mute?pid=0$COMMAND_DELIMITER"
    }

    "should set mute" {
      val expectedResponse = SetMuteResponse(
          Status(GroupedCommand(PLAYER, SET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("state", PlayerMuteState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setMute("0", PlayerMuteState.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_mute?pid=0&state=off$COMMAND_DELIMITER"
    }

    "should toggle mute" {
      val expectedResponse = ToggleMuteResponse(
          Status(GroupedCommand(PLAYER, TOGGLE_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.toggleMute("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/toggle_mute?pid=0$COMMAND_DELIMITER"
    }

    "should get play mode" {
      val expectedResponse = GetPlayModeResponse(
          Status(GroupedCommand(PLAYER, GET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayMode("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_play_mode?pid=0$COMMAND_DELIMITER"
    }

    "should set mute" {
      val expectedResponse = SetPlayModeResponse(
          Status(GroupedCommand(PLAYER, SET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("repeat", PlayRepeatState.ON)
              .add("shuffle", PlayShuffleState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayRepeatState.ON, PlayShuffleState.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&repeat=on&shuffle=off$COMMAND_DELIMITER"
    }
  }
}
