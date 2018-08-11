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

import com.google.protobuf.util.JsonFormat
import io.honnix.kheos.common.*
import io.honnix.kheos.common.Command.*
import io.honnix.kheos.common.CommandGroup.*
import io.honnix.kheos.proto.base.v1.*
import io.honnix.kheos.proto.base.v1.Result.fail
import io.honnix.kheos.proto.base.v1.Result.success
import io.honnix.kheos.proto.browse.v1.*
import io.honnix.kheos.proto.event.v1.ChangeEventResponse
import io.honnix.kheos.proto.event.v1.RegisterForChangeEventsResponse
import io.honnix.kheos.proto.group.v1.*
import io.honnix.kheos.proto.player.v1.*
import io.honnix.kheos.proto.system.v1.*
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.jmock.lib.concurrent.DeterministicScheduler
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit

private class QuietDeterministicScheduler : DeterministicScheduler() {
  private var isShutdown = false

  override fun shutdown() {
    isShutdown = true
  }

  override fun shutdownNow(): MutableList<Runnable> {
    isShutdown = true
    return mutableListOf()
  }

  override fun isShutdown() = isShutdown
}

internal class HeosClientImplTest : StringSpec() {
  private val scheduler = QuietDeterministicScheduler()
  private val socket = mock<Socket>()
  private val heosClient = HeosClientImpl("localhost", { socket }, scheduler)

  private fun <T : com.google.protobuf.Message> prepareInputOutput(response: T)
      : Pair<ByteArrayInputStream, ByteArrayOutputStream> {
    val input = JsonFormat.printer().omittingInsignificantWhitespace()
        .print(response).byteInputStream()
    `when`(socket.getInputStream()).thenReturn(input)

    val output = ByteArrayOutputStream()
    `when`(socket.getOutputStream()).thenReturn(output)

    return Pair(input, output)
  }

  init {
    heosClient.connect()

    "should create an instance of HeosClientImpl" {
      HeosClient.newInstance("localhost")::class shouldBe HeosClientImpl::class
    }

    "should reconnect" {
      `when`(socket.getOutputStream()).thenThrow(SocketException())

      shouldThrow<HeosClientException> {
        heosClient.checkAccount()
      }

      heosClient.reconnect()
      verify(socket).close()

      reset(socket)

      heosClient.reconnect()
      verify(socket, never()).close()

      heosClient.reconnect(true)
      verify(socket).close()
    }

    "should close" {
      heosClient.close()
      verify(socket).close()
    }

    "should schedule heartbeat" {
      val expectedResponse = HeartbeatResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, HEART_BEAT).toString())
              .setResult(success)
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/heart_beat$COMMAND_DELIMITER"

      heosClient.stopHeartbeat()
      scheduler.isShutdown shouldBe true
    }

    "should fail heartbeat" {
      val expectedResponse = HeartbeatResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, HEART_BEAT).toString())
              .setResult(fail)
              .setMessage(Message.Builder()
                  .add("eid", ErrorId.INTERNAL_ERROR.eid)
                  .add("text", "System Internal Error")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

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
      `when`(socket.getInputStream()).thenReturn(mock<ByteArrayInputStream>())
      doThrow(RuntimeException()).`when`(output).write(any<ByteArray>(), anyInt(), anyInt())

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)
    }

    "should check account" {
      val expectedResponse = CheckAccountResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, CHECK_ACCOUNT).toString())
              .setResult(success)
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.checkAccount()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account$COMMAND_DELIMITER"
    }

    "should fail to check account" {
      val expectedResponse = CheckAccountResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, CHECK_ACCOUNT).toString())
              .setResult(fail)
              .setMessage(Message.Builder()
                  .add("eid", ErrorId.INTERNAL_ERROR.eid)
                  .add("text", "System Internal Error")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

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

      shouldThrow<HeosClientException> {
        heosClient.checkAccount()
      }
    }

    "should sign in" {
      val expectedResponse = SignInResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, SIGN_IN).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("signed_in")
                  .add("un", "user@example.com")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.signIn("user@example.com", "bar")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/sign_in?un=user@example.com&pw=bar$COMMAND_DELIMITER"
    }

    "should sign out" {
      val expectedResponse = SignOutResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, SIGN_OUT).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("signed_out")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.signOut()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/sign_out$COMMAND_DELIMITER"
    }

    "should reboot" {
      val expectedResponse = RebootResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(SYSTEM, REBOOT).toString())
              .setResult(success)
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.reboot()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/reboot$COMMAND_DELIMITER"
    }

    "should get all players" {
      val expectedResponse = GetPlayersResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_PLAYERS).toString())
              .setResult(success)
              .build())
          .addPayload(Player.newBuilder()
              .setName("name0")
              .setPid("0")
              .setModel("model0")
              .setVersion("0.0")
              .setIp("192.168.1.100")
              .setNetwork("wifi")
              .setLineout(Player.Lineout.VARIABLE)
              .setSerial("ADAG0000")
              .setGid("100")
              .setControl(Player.Control.NETWORK))
          .addPayload(Player.newBuilder()
              .setName("name1")
              .setPid("1")
              .setModel("model1")
              .setVersion("0.1")
              .setIp("192.168.1.101")
              .setNetwork("wifi")
              .setLineout(Player.Lineout.FIXED)
              .setSerial("ADAG0000")
              .setGid("100")
              .setControl(Player.Control.NETWORK))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayers()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_players$COMMAND_DELIMITER"
    }

    "should get player info" {
      val expectedResponse = GetPlayerInfoResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_PLAYERS).toString())
              .setResult(success)
              .build())
          .setPayload(Player.newBuilder()
              .setName("name0")
              .setPid("0")
              .setModel("model0")
              .setVersion("0.0")
              .setIp("192.168.1.100")
              .setNetwork("wifi")
              .setLineout(Player.Lineout.VARIABLE)
              .setSerial("ADAG0000")
              .setGid("100")
              .setControl(Player.Control.NETWORK))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayerInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_player_info?pid=0$COMMAND_DELIMITER"
    }

    "should get play state" {
      val expectedResponse = GetPlayStateResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_PLAY_STATE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("state", "play")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayState("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_play_state?pid=0$COMMAND_DELIMITER"
    }

    "should set play state" {
      val expectedResponse = SetPlayStateResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_PLAY_STATE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("state", "play")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayState("0", PlayState.State.PLAY)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_state?pid=0&state=play$COMMAND_DELIMITER"
    }

    "should get now playing media" {
      val expectedResponse = GetNowPlayingMediaResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .setPayload(GetNowPlayingMediaResponse.NowPlayingMedia.newBuilder()
              .setType(io.honnix.kheos.proto.base.v1.MediaType.station)
              .setSong("song")
              .setAlbum("album")
              .setArtist("artist")
              .setImageUrl("http://example.com")
              .setAlbumId("0")
              .setMid("0")
              .setQid("0")
              .setSid("0")
              .setStation("station")
              .build())
          .addAllOptions(listOf(GetNowPlayingMediaResponse.PlayOptions.newBuilder()
              .addPlay(io.honnix.kheos.proto.base.v1.Option.newBuilder()
                  .setId(OptionId.ADD_TO_HEOS_FAVORITES)
                  .setName("Add to HEOS Favorites")
                  .build())
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getNowPlayingMedia("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_now_playing_media?pid=0$COMMAND_DELIMITER"
    }

    "should get player volume" {
      val expectedResponse = GetVolumeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_VOLUME).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("level", "10")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getVolume(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_volume?pid=0$COMMAND_DELIMITER"
    }

    "should set player volume" {
      val expectedResponse = SetVolumeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_VOLUME).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("level", "10")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setVolume(PLAYER, "0", 10)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_volume?pid=0&level=10$COMMAND_DELIMITER"
    }

    "should throw if player volume level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setVolume(PLAYER, "0", -1)
      }
    }

    "should player volume up" {
      val expectedResponse = VolumeUpResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("step", "3")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(PLAYER, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_up?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should player volume up with default step" {
      val expectedResponse = VolumeUpResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("step", "5")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_up?pid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if player volume up step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeUp(PLAYER, "0", -1)
      }
    }

    "should player volume down" {
      val expectedResponse = VolumeDownResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("step", "3")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(PLAYER, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_down?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should player volume down with default step" {
      val expectedResponse = VolumeDownResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("step", "5")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_down?pid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if player volume down step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeDown(PLAYER, "0", 11)
      }
    }

    "should get player mute" {
      val expectedResponse = GetMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("state", MuteState.State.ON.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMute(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_mute?pid=0$COMMAND_DELIMITER"
    }

    "should set player mute" {
      val expectedResponse = SetMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("state", MuteState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setMute(PLAYER, "0", MuteState.State.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_mute?pid=0&state=off$COMMAND_DELIMITER"
    }

    "should toggle player mute" {
      val expectedResponse = ToggleMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, TOGGLE_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("state", MuteState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.toggleMute(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/toggle_mute?pid=0$COMMAND_DELIMITER"
    }

    "should get play mode" {
      val expectedResponse = GetPlayModeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_PLAY_MODE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("repeat", PlayRepeatState.State.OFF.name.toLowerCase())
                  .add("shuffle", PlayShuffleState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayMode("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_play_mode?pid=0$COMMAND_DELIMITER"
    }

    "should set play mode" {
      val expectedResponse = SetPlayModeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_PLAY_MODE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("repeat", PlayRepeatState.State.OFF.name.toLowerCase())
                  .add("shuffle", PlayShuffleState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0",
          PlayRepeatState.State.ON_ALL, PlayShuffleState.State.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&repeat=on_all&shuffle=off$COMMAND_DELIMITER"
    }

    "should set play mode repeat" {
      val expectedResponse = SetPlayModeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_PLAY_MODE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("repeat", PlayRepeatState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayRepeatState.State.ON_ALL)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&repeat=on_all$COMMAND_DELIMITER"
    }

    "should set play mode shuffle" {
      val expectedResponse = SetPlayModeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SET_PLAY_MODE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("shuffle", PlayShuffleState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayShuffleState.State.ON)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&shuffle=on$COMMAND_DELIMITER"
    }

    "should get queue" {
      val expectedResponse = GetQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .addAllPayload(listOf(GetQueueResponse.Element.newBuilder()
              .setSong("song")
              .setAlbum("album")
              .setArtist("artist")
              .setImageUrl("http://example.com")
              .setQid("0")
              .setMid("0")
              .setAlbum("0")
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getQueue("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_queue?pid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should throw if range start < 0" {
      shouldThrow<IllegalArgumentException> {
        heosClient.getQueue("0", IntRange(-1, 10))
      }
    }

    "should get queue if range is empty" {
      val expectedResponse = GetQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, GET_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .addAllPayload(listOf(GetQueueResponse.Element.newBuilder()
              .setSong("song")
              .setAlbum("album")
              .setArtist("artist")
              .setImageUrl("http://example.com")
              .setQid("0")
              .setMid("0")
              .setAlbum("0")
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getQueue("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_queue?pid=0$COMMAND_DELIMITER"
    }

    "should play queue" {
      val expectedResponse = PlayQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, PLAY_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("qid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playQueue("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_queue?pid=0&qid=0$COMMAND_DELIMITER"
    }

    "should remove from queue" {
      val expectedResponse = RemoveFromQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("qid", "0,1,2,3")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.removeFromQueue("0", listOf("0", "1", "2", "3"))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/remove_from_queue?pid=0&qid=0,1,2,3$COMMAND_DELIMITER"
    }

    "should throw if no qids" {
      shouldThrow<IllegalArgumentException> {
        heosClient.removeFromQueue("0", emptyList())
      }
    }

    "should save queue" {
      val expectedResponse = SaveQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, SAVE_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("name", "foo bar")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.saveQueue("0", "foo bar")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/save_queue?pid=0&name=foo bar$COMMAND_DELIMITER"
    }

    "should clear queue" {
      val expectedResponse = ClearQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, CLEAR_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.clearQueue("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/clear_queue?pid=0$COMMAND_DELIMITER"
    }

    "should play previous" {
      val expectedResponse = PlayPreviousResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, PLAY_PREVIOUS).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playPrevious("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_previous?pid=0$COMMAND_DELIMITER"
    }

    "should play next" {
      val expectedResponse = PlayNextResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(PLAYER, PLAY_NEXT).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playNext("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_next?pid=0$COMMAND_DELIMITER"
    }

    "should get groups" {
      val expectedResponse = GetGroupsResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, GET_GROUPS).toString())
              .setResult(success)
              .build())
          .addPayload(Group.newBuilder()
              .setName("foo")
              .setGid("0")
              .addAllPlayers(listOf(
                  Group.Player.newBuilder()
                      .setName("foofoo")
                      .setPid("0")
                      .setRole(Group.Player.Role.leader)
                      .build(),
                  Group.Player.newBuilder()
                      .setName("foobar")
                      .setPid("1")
                      .setRole(Group.Player.Role.member)
                      .build()
              )))
          .addPayload(Group.newBuilder()
              .setName("bar")
              .setGid("1")
              .addPlayers(
                  Group.Player.newBuilder()
                      .setName("barbar")
                      .setPid("1")
                      .setRole(Group.Player.Role.leader)
                      .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getGroups()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_groups$COMMAND_DELIMITER"
    }

    "should get group info" {
      val expectedResponse = GetGroupInfoResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, GET_GROUP_INFO).toString())
              .setResult(success)
              .build())
          .setPayload(Group.newBuilder()
              .setName("foo")
              .setGid("0")
              .addAllPlayers(listOf(
                  Group.Player.newBuilder()
                      .setName("foofoo")
                      .setPid("0")
                      .setRole(Group.Player.Role.leader)
                      .build(),
                  Group.Player.newBuilder()
                      .setName("foobar")
                      .setPid("1")
                      .setRole(Group.Player.Role.member)
                      .build())))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getGroupInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_group_info?gid=0$COMMAND_DELIMITER"
    }

    "should set group" {
      val expectedResponse = SetGroupResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, SET_GROUP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("name", "foo")
                  .add("pid", "0,1,2")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setGroup("0", listOf("1", "2"))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_group?pid=0,1,2$COMMAND_DELIMITER"
    }

    "should throw if no member" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setGroup("0", emptyList())
      }
    }

    "should delete group" {
      val expectedResponse = DeleteGroupResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, SET_GROUP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.deleteGroup("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_group?pid=0$COMMAND_DELIMITER"
    }

    "should get group volume" {
      val expectedResponse = GetVolumeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, GET_VOLUME).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("level", "10")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getVolume(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_volume?gid=0$COMMAND_DELIMITER"
    }

    "should set group volume" {
      val expectedResponse = SetVolumeResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, SET_VOLUME).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("level", "10")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setVolume(GROUP, "0", 10)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_volume?gid=0&level=10$COMMAND_DELIMITER"
    }

    "should throw if group volume level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setVolume(GROUP, "0", -1)
      }
    }

    "should group volume up" {
      val expectedResponse = VolumeUpResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("step", "3")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(GROUP, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_up?gid=0&step=3$COMMAND_DELIMITER"
    }

    "should group volume up with default step" {
      val expectedResponse = VolumeUpResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("step", "5")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_up?gid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if group volume up step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeUp(GROUP, "0", -1)
      }
    }

    "should group volume down" {
      val expectedResponse = VolumeDownResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("step", "3")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(GROUP, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_down?gid=0&step=3$COMMAND_DELIMITER"
    }

    "should group volume down with default step" {
      val expectedResponse = VolumeDownResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, VOLUME_UP).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("step", "5")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_down?gid=0&step=5$COMMAND_DELIMITER"
    }

    "should throw if group volume down step level is illegal" {
      shouldThrow<IllegalArgumentException> {
        heosClient.volumeDown(GROUP, "0", 11)
      }
    }

    "should get group mute" {
      val expectedResponse = GetMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, GET_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("state", MuteState.State.ON.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMute(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_mute?gid=0$COMMAND_DELIMITER"
    }

    "should set group mute" {
      val expectedResponse = SetMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, SET_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("state", MuteState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setMute(GROUP, "0", MuteState.State.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_mute?gid=0&state=off$COMMAND_DELIMITER"
    }

    "should toggle group mute" {
      val expectedResponse = ToggleMuteResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(GROUP, TOGGLE_MUTE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("gid", "0")
                  .add("state", MuteState.State.OFF.name.toLowerCase())
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.toggleMute(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/toggle_mute?gid=0$COMMAND_DELIMITER"
    }

    "should get music sources" {
      val expectedResponse = GetMusicSourcesResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES).toString())
              .setResult(success)
              .build())
          .addAllPayload(listOf(
              MusicSource.newBuilder()
                  .setName("foo")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.heos_server)
                  .setSid("0")
                  .build(),
              MusicSource.newBuilder()
                  .setName("bar")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.dlna_server)
                  .setSid("1")
                  .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMusicSources()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_music_sources$COMMAND_DELIMITER"
    }

    "should get music source info" {
      val expectedResponse = GetMusicSourceInfoResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, GET_SOURCE_INFO).toString())
              .setResult(success)
              .build())
          .setPayload(MusicSource.newBuilder()
              .setName("foo")
              .setImageUrl("http://example.com")
              .setType(MusicSource.MusicSourceType.heos_server)
              .setSid("0")
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMusicSourceInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_source_info?sid=0$COMMAND_DELIMITER"
    }

    "should browse media sources" {
      val expectedResponse = BrowseMediaSourcesResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("returned", 2)
                  .add("count", 2)
                  .build().toString())
              .build())
          .addAllPayload(listOf(
              MusicSource.newBuilder()
                  .setName("foo")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.heos_server)
                  .setSid("0")
                  .build(),
              MusicSource.newBuilder()
                  .setName("bar")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.dlna_server)
                  .setSid("1")
                  .build()))
          .addAllOptions(listOf(BrowseOptions.newBuilder()
              .addBrowse(io.honnix.kheos.proto.base.v1.Option.newBuilder()
                  .setId(OptionId.CREATE_NEW_STATION)
                  .setName("Create New Station")
                  .build())
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseMediaSources("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse music sources if range is empty" {
      val expectedResponse = BrowseMediaSourcesResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("returned", 2)
                  .add("count", 2)
                  .build().toString())
              .build())
          .addAllPayload(listOf(
              MusicSource.newBuilder()
                  .setName("foo")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.heos_server)
                  .setSid("0")
                  .build(),
              MusicSource.newBuilder()
                  .setName("bar")
                  .setImageUrl("http://example.com")
                  .setType(MusicSource.MusicSourceType.dlna_server)
                  .setSid("1")
                  .build()))
          .addAllOptions(listOf(BrowseOptions.newBuilder()
              .addBrowse(io.honnix.kheos.proto.base.v1.Option.newBuilder()
                  .setId(OptionId.CREATE_NEW_STATION)
                  .setName("Create New Station")
                  .build())
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseMediaSources("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when browsing music sources" {
      shouldThrow<IllegalArgumentException> {
        heosClient.browseMediaSources("0", IntRange(-1, 10))
      }
    }

    "should browse top music" {
      val expectedResponse = BrowseTopMusicResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseTopMusic("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse top music if range is empty" {
      val expectedResponse = BrowseTopMusicResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseTopMusic("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when browsing top music" {
      shouldThrow<IllegalArgumentException> {
        heosClient.browseTopMusic("0", IntRange(-1, 10))
      }
    }

    "should browse source containers" {
      val expectedResponse = BrowseSourceContainersResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .addAllOptions(listOf(BrowseOptions.newBuilder()
              .addBrowse(io.honnix.kheos.proto.base.v1.Option.newBuilder()
                  .setId(OptionId.ADD_PLAYLIST_TO_LIBRARY)
                  .setName("Add Playlist to Library")
                  .build())
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseSourceContainers("0", "0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&cid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse source containers if range is empty" {
      val expectedResponse = BrowseSourceContainersResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .addAllOptions(listOf(BrowseOptions.newBuilder()
              .addBrowse(io.honnix.kheos.proto.base.v1.Option.newBuilder()
                  .setId(OptionId.ADD_PLAYLIST_TO_LIBRARY)
                  .setName("Add Playlist to Library")
                  .build())
              .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseSourceContainers("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&cid=0$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when browsing source containers" {
      shouldThrow<IllegalArgumentException> {
        heosClient.browseSourceContainers("0", "0", IntRange(-1, 10))
      }
    }

    "should get search criteria" {
      val expectedResponse = GetSearchCriteriaResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, GET_SEARCH_CRITERIA).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .build().toString())
              .build())
          .addAllPayload(listOf(
              GetSearchCriteriaResponse.SearchCriteria.newBuilder()
                  .setName("Artist")
                  .setScid(Scid.ARTIST)
                  .setWildcard(YesNo.no)
                  .build(),
              GetSearchCriteriaResponse.SearchCriteria.newBuilder()
                  .setName("Album")
                  .setScid(Scid.ALBUM)
                  .setWildcard(YesNo.yes)
                  .build()
              ))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getSearchCriteria("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_search_criteria?sid=0$COMMAND_DELIMITER"
    }

    "should search" {
      val expectedResponse = SearchResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, SEARCH).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("search", "*")
                  .add("scid", 1)
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.search("0", Scid.ARTIST, "*", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/search?sid=0&search=*&scid=1&range=0,10$COMMAND_DELIMITER"
    }

    "should search if range is empty" {
      val expectedResponse = SearchResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, SEARCH).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("search", "*")
                  .add("scid", 1)
                  .add("returned", 6)
                  .add("count", 6)
                  .build().toString())
              .build())
          .addAllPayload(mediaList())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.search("0", Scid.ARTIST, "*")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/search?sid=0&search=*&scid=1$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when searching" {
      shouldThrow<IllegalArgumentException> {
        heosClient.search("0", Scid.ARTIST, "*", IntRange(-1, 10))
      }
    }

    "should play stream" {
      val expectedResponse = PlayStreamResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("mid", "0")
                  .add("name", "foo")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playStream("0", "0", "0", "foo", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/play_stream?pid=0&sid=0&cid=0&mid=0&name=foo$COMMAND_DELIMITER"
    }

    "should play stream without cid" {
      val expectedResponse = PlayStreamResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("sid", "0")
                  .add("mid", "0")
                  .add("name", "foo")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playStream("0", "0", "0", "foo")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/play_stream?pid=0&sid=0&mid=0&name=foo$COMMAND_DELIMITER"
    }

    "should play input" {
      val expectedResponse = PlayInputResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("mid", "0")
                  .add("spid", "0")
                  .add("input", "inputs/aux_in_1")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playInput("0", "0", "0", "inputs/aux_in_1")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/play_input?pid=0&mid=0&spid=0&input=inputs/aux_in_1$COMMAND_DELIMITER"
    }

    "should play input from specified input" {
      val expectedResponse = PlayInputResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("input", "inputs/aux_in_1")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playInput("0", input = "inputs/aux_in_1")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/play_input?pid=0&input=inputs/aux_in_1$COMMAND_DELIMITER"
    }

    "should add container to queue" {
      val expectedResponse = AddToQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("aid", 3)
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.addToQueue("0", "0", "0",
          AddToQueueRequest.AddCriteriaId.ADD_TO_END)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/add_to_queue?pid=0&sid=0&cid=0&aid=3$COMMAND_DELIMITER"
    }

    "should add track to queue" {
      val expectedResponse = AddToQueueResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("pid", "0")
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("mid", "0")
                  .add("aid", 3)
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.addToQueue("0", "0", "0",
          AddToQueueRequest.AddCriteriaId.ADD_TO_END, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/add_to_queue?pid=0&sid=0&cid=0&mid=0&aid=3$COMMAND_DELIMITER"
    }

    "should rename playlist" {
      val expectedResponse = RenamePlaylistResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, RENAME_PLAYLIST).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("name", "foo")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.renamePlaylist("0", "0", "foo")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/rename_playlist?sid=0&cid=0&name=foo$COMMAND_DELIMITER"
    }

    "should delete playlist" {
      val expectedResponse = DeletePlaylistResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, DELETE_PLAYLIST).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("cid", "0")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.deletePlaylist("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/delete_playlist?sid=0&cid=0$COMMAND_DELIMITER"
    }

    "should retrieve metadata" {
      val expectedResponse = RetrieveMetadataResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, RETRIEVE_METADATA).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("sid", "0")
                  .add("cid", "0")
                  .add("returned", 2)
                  .add("count", 2)
                  .build().toString())
              .build())
          .addAllPayload(listOf(
              RetrieveMetadataResponse.Metadata.newBuilder()
                  .setAlbumdId("0")
                  .addAllImages(listOf(
                      RetrieveMetadataResponse.Metadata.Image.newBuilder()
                          .setImageUrl("http://example.com")
                          .setWidth(10.0)
                          .build(),
                      RetrieveMetadataResponse.Metadata.Image.newBuilder()
                          .setImageUrl("http://example.com")
                          .setWidth(12.0)
                          .build()
                      ))
                  .build()))
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.retrieveMetadata("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/retrieve_metadata?sid=0&cid=0$COMMAND_DELIMITER"
    }

    "should set service option" {
      val expectedResponse = SetServiceOptionResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, SET_SERVICE_OPTION).toString())
              .setResult(success)
              .setMessage(Message.Builder()
                  .add("option", OptionId.CREATE_NEW_STATION.number)
                  .add("sid", "0")
                  .add("name", "foo")
                  .build().toString())
              .build())
          .build()

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setServiceOption(OptionId.CREATE_NEW_STATION,
          AttributesBuilder()
              .add("sid", "0")
              .add("name", "foo")
              .build())

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/set_service_option?option=13&sid=0&name=foo$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when setting service option" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setServiceOption(OptionId.CREATE_NEW_STATION,
            AttributesBuilder()
                .add("sid", "0")
                .add("name", "foo")
                .build(),
            IntRange(-1, 10))
      }
    }

    "should throw if specify range for option other than OptionId.CREATE_NEW_STATION" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setServiceOption(OptionId.ADD_TO_HEOS_FAVORITES,
            AttributesBuilder()
                .add("pid", "0")
                .build(),
            IntRange(0, 10))
      }
    }
  }

  private fun mediaList(): List<Media> {
    return listOf(
        Media.newBuilder()
            .setContainer(YesNo.yes)
            .setPlayable(YesNo.no)
            .setType(MediaType.artist)
            .setName("artist name")
            .setImageUrl("http://example.com")
            .setCid("0")
            .setMid("0")
            .build(),
        Media.newBuilder()
            .setContainer(YesNo.yes)
            .setPlayable(YesNo.yes)
            .setType(MediaType.album)
            .setName("album name")
            .setImageUrl("http://example.com")
            .setArtist("artist name")
            .setCid("0")
            .setMid("0")
            .build(),
        Media.newBuilder()
            .setContainer(YesNo.no)
            .setPlayable(YesNo.yes)
            .setType(MediaType.song)
            .setName("song name")
            .setImageUrl("http://example.com")
            .setArtist("artist name")
            .setAlbum("album name")
            .setMid("0")
            .build(),
        Media.newBuilder()
            .setContainer(YesNo.yes)
            .setPlayable(YesNo.no)
            .setType(MediaType.genre)
            .setName("genre name")
            .setImageUrl("http://example.com")
            .setCid("0")
            .setMid("0")
            .build(),
        Media.newBuilder()
            .setContainer(YesNo.yes)
            .setPlayable(YesNo.no)
            .setType(MediaType.container)
            .setName("container name")
            .setImageUrl("http://example.com")
            .setCid("0")
            .setMid("0")
            .build(),
        Media.newBuilder()
            .setContainer(YesNo.no)
            .setPlayable(YesNo.yes)
            .setType(MediaType.station)
            .setName("station name")
            .setImageUrl("http://example.com")
            .setMid("0")
            .build())
  }
}

internal class HeosChangeEventsClientTest : StringSpec() {
  private val socketExecutorService = QuietDeterministicScheduler()
  private val listenerExecutorService = QuietDeterministicScheduler()
  private val socket = mock<Socket>()
  private val heosChangeEventsClient = HeosChangeEventsClientImpl("localhost", { socket },
      socketExecutorService, listenerExecutorService)

  private fun start(): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
    val expectedResponse = RegisterForChangeEventsResponse.newBuilder()
        .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
            .setCommand(GroupedCommand(CommandGroup.BROWSE, REGISTER_FOR_CHANGE_EVENTS).toString())
            .setResult(success)
            .setMessage(Message.Builder()
                .add("enable", "on")
                .build().toString())
            .build())
        .build()

    val input = JsonFormat.printer().omittingInsignificantWhitespace()
        .print(expectedResponse).byteInputStream()
    `when`(socket.getInputStream()).thenReturn(input)

    val output = ByteArrayOutputStream()
    `when`(socket.getOutputStream()).thenReturn(output)

    heosChangeEventsClient.connect()
    return Pair(input, output)
  }

  init {
    "should create an instance of HeosChangeEventsClientImpl" {
      HeosChangeEventsClient.newInstance("localhost")::class shouldBe HeosChangeEventsClientImpl::class
    }

    "should register and unregister listeners" {
      val listener = mock<ChangeEventListener>()
      val id0 = heosChangeEventsClient.register(listener)
      id0 shouldBe 0
      val id1 = heosChangeEventsClient.register(listener)
      id1 shouldBe 1
      heosChangeEventsClient.unregister(id0)
      heosChangeEventsClient.unregister(id1)
    }

    "should start and close" {
      val (input, output) = start()

      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://system/register_for_change_events?enable=on$COMMAND_DELIMITER"

      heosChangeEventsClient.close()

      verify(socket).close()
      socketExecutorService.isShutdown shouldBe true
      listenerExecutorService.isShutdown shouldBe true
    }

    "should fail to start" {
      val expectedResponse = RegisterForChangeEventsResponse.newBuilder()
          .setHeos(io.honnix.kheos.proto.base.v1.Heos.newBuilder()
              .setCommand(GroupedCommand(CommandGroup.BROWSE, REGISTER_FOR_CHANGE_EVENTS).toString())
              .setResult(fail)
              .setMessage(Message.Builder()
                  .add("eid", ErrorId.INTERNAL_ERROR.eid)
                  .add("text", "System Internal Error")
                  .build().toString())
              .build())
          .build()

      val input = JsonFormat.printer().omittingInsignificantWhitespace()
          .print(expectedResponse).byteInputStream()
      `when`(socket.getInputStream()).thenReturn(input)

      val output = ByteArrayOutputStream()
      `when`(socket.getOutputStream()).thenReturn(output)

      val exception = shouldThrow<HeosCommandException> {
        heosChangeEventsClient.connect()
      }

      exception.eid shouldBe ErrorId.INTERNAL_ERROR
      exception.text shouldBe "System Internal Error"
    }

    "should call listener for event" {
      val listener = mock<ChangeEventListener>()
      heosChangeEventsClient.register(listener)

      start()

      val changeEvent = ChangeEventResponse.ChangeEvent.newBuilder()
          .setCommand(ChangeEventResponse.ChangeEvent.ChangeEventCommand.player_now_playing_progress)
          .setMessage(Message.Builder()
              .add("pid", "0")
              .add("cur_pos", "0")
              .add("duration", "0")
              .build().toString())
          .build()
      val changeEventResponse = ChangeEventResponse.newBuilder().setHeos(changeEvent)

      val input = JsonFormat.printer().omittingInsignificantWhitespace()
          .print(changeEventResponse).byteInputStream()
      doReturn(input).doThrow(SocketException("Socket closed")).`when`(socket).getInputStream()

      socketExecutorService.tick(1, TimeUnit.SECONDS)
      listenerExecutorService.tick(1, TimeUnit.SECONDS)

      verify(listener).onEvent(changeEvent)

      heosChangeEventsClient.close()
    }

    "should call listener for exception" {
      val listener = mock<ChangeEventListener>()
      heosChangeEventsClient.register(listener)

      start()

      val socketException = SocketException()
      val ioException = IOException()
      doThrow(socketException)
          .doThrow(ioException)
          .doThrow(SocketException("Socket closed")).`when`(socket).getInputStream()

      socketExecutorService.tick(1, TimeUnit.SECONDS)
      listenerExecutorService.tick(1, TimeUnit.SECONDS)

      verify(listener).onException(socketException)
      verify(listener).onException(ioException)

      heosChangeEventsClient.close()
    }
  }
}

fun main(args: Array<String>) {
  val client = HeosChangeEventsClient.newInstance("heos")

  client.register(object : ChangeEventListener {
    override fun onEvent(event: ChangeEventResponse.ChangeEvent) {
      println(event)
    }
  })
  client.register(object : ChangeEventListener {
    override fun onEvent(event: ChangeEventResponse.ChangeEvent) {
      System.err.println(event)
    }
  })
  client.connect()

  Thread.sleep(1000 * 10)

  client.close()
}
