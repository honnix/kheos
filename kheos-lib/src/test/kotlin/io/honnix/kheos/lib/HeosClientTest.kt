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
import io.honnix.kheos.lib.MusicSourceType.*
import io.honnix.kheos.lib.PlayState.PLAY
import io.honnix.kheos.lib.YesNo.*
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
              "station"),
          listOf(mapOf("play" to
              listOf(mapOf("id" to "19", "name" to "Add to HEOS Favorites")))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getNowPlayingMedia("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_now_playing_media?pid=0$COMMAND_DELIMITER"
    }

    "should get player volume" {
      val expectedResponse = GetVolumeResponse(
          Status(GroupedCommand(PLAYER, GET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("level", "10")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getVolume(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_volume?pid=0$COMMAND_DELIMITER"
    }

    "should set player volume" {
      val expectedResponse = SetVolumeResponse(
          Status(GroupedCommand(PLAYER, SET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("level", "10")
              .build()))

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
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(PLAYER, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(PLAYER, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_up?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should player volume up with default step" {
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(PLAYER, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "5")
              .build()))

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
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(PLAYER, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(PLAYER, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/volume_down?pid=0&step=3$COMMAND_DELIMITER"
    }

    "should player volume down with default step" {
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(PLAYER, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("step", "5")
              .build()))

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
      val expectedResponse = GetMuteResponse(
          Status(GroupedCommand(PLAYER, GET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMute(PLAYER, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_mute?pid=0$COMMAND_DELIMITER"
    }

    "should set player mute" {
      val expectedResponse = SetMuteResponse(
          Status(GroupedCommand(PLAYER, SET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("state", MuteState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setMute(PLAYER, "0", MuteState.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_mute?pid=0&state=off$COMMAND_DELIMITER"
    }

    "should toggle player mute" {
      val expectedResponse = ToggleMuteResponse(
          Status(GroupedCommand(PLAYER, TOGGLE_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.toggleMute(PLAYER, "0")

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

    "should get queue" {
      val expectedResponse = GetQueueResponse(
          Status(GroupedCommand(PLAYER, GET_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()),
          listOf(QueueItem("song", "album", "artist",
              URL("http://example.com"), "0", "0", "0")))

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
      val expectedResponse = GetQueueResponse(
          Status(GroupedCommand(PLAYER, GET_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()),
          listOf(QueueItem("song", "album", "artist",
              URL("http://example.com"), "0", "0", "0")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getQueue("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_queue?pid=0$COMMAND_DELIMITER"
    }

    "should play queue" {
      val expectedResponse = PlayQueueResponse(
          Status(GroupedCommand(PLAYER, PLAY_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("qid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playQueue("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_queue?pid=0&qid=0$COMMAND_DELIMITER"
    }

    "should remove from queue" {
      val expectedResponse = RemoveFromQueueResponse(
          Status(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("qid", "0,1,2,3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.removeFromQueue("0", listOf("0", "1", "2", "3"))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/remove_from_queue?pid=0&qid=0,1,2,3$COMMAND_DELIMITER"
    }

    "should throw if no qid" {
      shouldThrow<IllegalArgumentException> {
        heosClient.removeFromQueue("0", emptyList())
      }
    }

    "should save queue" {
      val expectedResponse = SaveQueueResponse(
          Status(GroupedCommand(PLAYER, SAVE_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("name", "foo bar")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.saveQueue("0", "foo bar")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/save_queue?pid=0&name=foo bar$COMMAND_DELIMITER"
    }

    "should clear queue" {
      val expectedResponse = ClearQueueResponse(
          Status(GroupedCommand(PLAYER, CLEAR_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.clearQueue("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/clear_queue?pid=0$COMMAND_DELIMITER"
    }

    "should play next" {
      val expectedResponse = PlayNextResponse(
          Status(GroupedCommand(PLAYER, PLAY_NEXT),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playNext("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_next?pid=0$COMMAND_DELIMITER"
    }

    "should play previous" {
      val expectedResponse = PlayPreviousResponse(
          Status(GroupedCommand(PLAYER, PLAY_PREVIOUS),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playPrevious("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/play_previous?pid=0$COMMAND_DELIMITER"
    }

    "should get groups" {
      val expectedResponse = GetGroupsResponse(
          Status(GroupedCommand(GROUP, GET_GROUPS),
              Result.SUCCESS, Message()),
          listOf(
              Group("foo", "0",
                  listOf(GroupedPlayer("foofoo", "0", Role.LEADER),
                      GroupedPlayer("foobar", "1", Role.MEMBER))),
              Group("bar", "1",
                  listOf(GroupedPlayer("barbar", "1", Role.LEADER)))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getGroups()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_groups$COMMAND_DELIMITER"
    }

    "should get group info" {
      val expectedResponse = GetGroupInfoResponse(
          Status(GroupedCommand(GROUP, GET_GROUPS),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .build()),
          Group("foo", "0",
              listOf(GroupedPlayer("foofoo", "0", Role.LEADER),
                  GroupedPlayer("foobar", "1", Role.MEMBER))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getGroupInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_group_info?gid=0$COMMAND_DELIMITER"
    }

    "should set group" {
      val expectedResponse = SetGroupResponse(
          Status(GroupedCommand(GROUP, SET_GROUP),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("name", "foo")
              .add("pid", "0,1,2")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setGroup(listOf("0", "1", "2"))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_group?pid=0,1,2$COMMAND_DELIMITER"
    }

    "should get group volume" {
      val expectedResponse = GetVolumeResponse(
          Status(GroupedCommand(GROUP, GET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("level", "10")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getVolume(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_volume?gid=0$COMMAND_DELIMITER"
    }

    "should set group volume" {
      val expectedResponse = SetVolumeResponse(
          Status(GroupedCommand(GROUP, SET_VOLUME),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("level", "10")
              .build()))

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
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(GROUP, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeUp(GROUP, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_up?gid=0&step=3$COMMAND_DELIMITER"
    }

    "should group volume up with default step" {
      val expectedResponse = VolumeUpResponse(
          Status(GroupedCommand(GROUP, VOLUME_UP),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("step", "5")
              .build()))

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
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(GROUP, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("step", "3")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.volumeDown(GROUP, "0", 3)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/volume_down?gid=0&step=3$COMMAND_DELIMITER"
    }

    "should group volume down with default step" {
      val expectedResponse = VolumeDownResponse(
          Status(GroupedCommand(GROUP, VOLUME_DOWN),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("step", "5")
              .build()))

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
      val expectedResponse = GetMuteResponse(
          Status(GroupedCommand(GROUP, GET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMute(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/get_mute?gid=0$COMMAND_DELIMITER"
    }

    "should set group mute" {
      val expectedResponse = SetMuteResponse(
          Status(GroupedCommand(GROUP, SET_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("state", MuteState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setMute(GROUP, "0", MuteState.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_mute?gid=0&state=off$COMMAND_DELIMITER"
    }

    "should toggle group mute" {
      val expectedResponse = ToggleMuteResponse(
          Status(GroupedCommand(GROUP, TOGGLE_MUTE),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.toggleMute(GROUP, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/toggle_mute?gid=0$COMMAND_DELIMITER"
    }

    "should get music sources" {
      val expectedResponse = GetMusicSourcesResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES),
              Result.SUCCESS, Message()),
          listOf(
              MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "0"),
              MusicSource("bar", URL("http://example.com"), DLNA_SERVER, "1")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMusicSources()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_music_sources$COMMAND_DELIMITER"
    }

    "should get music source info" {
      val expectedResponse = GetMusicSourceInfoResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCE_INFO),
              Result.SUCCESS, Message()),
          MusicSource("bar", URL("http://example.com"), DLNA_SERVER, "0"))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getMusicSourceInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_source_info?sid=0$COMMAND_DELIMITER"
    }

    "should browse music sources" {
      val expectedResponse = BrowseMediaSourcesResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 2)
              .add("count", 2)
              .build()),
          listOf(
              MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "100"),
              MusicSource("bar", URL("http://example.com"), HEOS_SERVICE, "101")),
          listOf(mapOf("browse" to
              listOf(mapOf("id" to "13", "name" to "create new station")))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseMusicSources("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse music sources if range is empty" {
      val expectedResponse = BrowseMediaSourcesResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 2)
              .add("count", 2)
              .build()),
          listOf(
              MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "100"),
              MusicSource("bar", URL("http://example.com"), HEOS_SERVICE, "101")),
          listOf(mapOf("browse" to
              listOf(mapOf("id" to "13", "name" to "create new station")))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseMusicSources("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when browsing music sources" {
      shouldThrow<IllegalArgumentException> {
        heosClient.browseMusicSources("0", IntRange(-1, 10))
      }
    }

    "should browse top music" {
      val expectedResponse = BrowseTopMusicResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MusicContainerArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MusicContainerAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MusicContainerSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MusicContainerGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MusicContainerContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MusicContainerStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseTopMusic("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse top music if range is empty" {
      val expectedResponse = BrowseTopMusicResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MusicContainerArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MusicContainerAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MusicContainerSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MusicContainerGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MusicContainerContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MusicContainerStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")))

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
      val expectedResponse = BrowseSourceContainersResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MusicContainerArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MusicContainerAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MusicContainerSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MusicContainerGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MusicContainerContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MusicContainerStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")),
          listOf(mapOf("browse" to
              listOf(mapOf("id" to "4", "name" to "Add Playlist to Library")))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseSourceContainers("0", "0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&cid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse source containers if range is empty" {
      val expectedResponse = BrowseSourceContainersResponse(
          Status(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MusicContainerArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MusicContainerAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MusicContainerSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MusicContainerGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MusicContainerContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MusicContainerStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")),
          listOf(mapOf("browse" to
              listOf(mapOf("id" to "4", "name" to "Add Playlist to Library")))))

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
  }
}
