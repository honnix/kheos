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

import io.honnix.kheos.common.*
import io.honnix.kheos.common.Command.*
import io.honnix.kheos.common.CommandGroup.*
import io.honnix.kheos.common.Control.NETWORK
import io.honnix.kheos.common.MediaType.STATION
import io.honnix.kheos.common.MusicSourceType.*
import io.honnix.kheos.common.PlayState.PLAY
import io.honnix.kheos.common.Result
import io.honnix.kheos.common.YesNo.*
import io.kotlintest.matchers.*
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.jmock.lib.concurrent.DeterministicScheduler
import org.mockito.Mockito.*
import java.io.*
import java.net.*
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

class HeosClientImplTest : StringSpec() {
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
      val response = HeartbeatResponse(
          Heos(GroupedCommand(SYSTEM, HEART_BEAT),
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
          Heos(GroupedCommand(SYSTEM, HEART_BEAT),
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
          Heos(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
              Result.SUCCESS, Message()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.checkAccount()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account$COMMAND_DELIMITER"
    }

    "should fail to check account" {
      val response = CheckAccountResponse(
          Heos(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
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

      shouldThrow<HeosClientException> {
        heosClient.checkAccount()
      }
    }

    "should sign in" {
      val expectedResponse = SignInResponse(
          Heos(GroupedCommand(SYSTEM, SIGN_IN),
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
          Heos(GroupedCommand(SYSTEM, SIGN_OUT),
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
          Heos(GroupedCommand(SYSTEM, REBOOT),
              Result.SUCCESS, Message()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.reboot()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/reboot$COMMAND_DELIMITER"
    }

    "should get all players" {
      val expectedResponse = GetPlayersResponse(
          Heos(GroupedCommand(PLAYER, GET_PLAYERS),
              Result.SUCCESS, Message()),
          listOf(
              Player("name0", "0", "model0",
                  "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE,
                  "ADAG0000"),
              Player("name1", "1", "model1",
                  "0.1", "192.168.1.101", "wifi", Lineout.FIXED,
                  "ADAG0000", "100", NETWORK)))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayers()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_players$COMMAND_DELIMITER"
    }

    "should get player info" {
      val expectedResponse = GetPlayerInfoResponse(
          Heos(GroupedCommand(PLAYER, GET_PLAYER_INFO),
              Result.SUCCESS, Message()),
          Player("name0", "0", "model0",
              "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE, "ADAG0000"))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayerInfo("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_player_info?pid=0$COMMAND_DELIMITER"
    }

    "should get play state" {
      val expectedResponse = GetPlayStateResponse(
          Heos(GroupedCommand(PLAYER, GET_PLAY_STATE),
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
          Heos(GroupedCommand(PLAYER, SET_PLAY_STATE),
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
          Heos(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()),
          NowPlayingMedia(STATION, "song", "album", "artist",
              URL("http://example.com"), "0", "0", "0", "0",
              "station"),
          listOf(mapOf("play" to
              listOf(Option.ADD_TO_HEOS_FAVORITES))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getNowPlayingMedia("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_now_playing_media?pid=0$COMMAND_DELIMITER"
    }

    "should get player volume" {
      val expectedResponse = GetVolumeResponse(
          Heos(GroupedCommand(PLAYER, GET_VOLUME),
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
          Heos(GroupedCommand(PLAYER, SET_VOLUME),
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
          Heos(GroupedCommand(PLAYER, VOLUME_UP),
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
          Heos(GroupedCommand(PLAYER, VOLUME_UP),
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
          Heos(GroupedCommand(PLAYER, VOLUME_DOWN),
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
          Heos(GroupedCommand(PLAYER, VOLUME_DOWN),
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
          Heos(GroupedCommand(PLAYER, GET_MUTE),
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
          Heos(GroupedCommand(PLAYER, SET_MUTE),
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
          Heos(GroupedCommand(PLAYER, TOGGLE_MUTE),
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
          Heos(GroupedCommand(PLAYER, GET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("repeat", PlayRepeatState.OFF)
              .add("shuffle", PlayShuffleState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getPlayMode("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/get_play_mode?pid=0$COMMAND_DELIMITER"
    }

    "should set play mode" {
      val expectedResponse = SetPlayModeResponse(
          Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("repeat", PlayRepeatState.ON_ALL)
              .add("shuffle", PlayShuffleState.OFF)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayRepeatState.ON_ALL,
          PlayShuffleState.OFF)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&repeat=on_all&shuffle=off$COMMAND_DELIMITER"
    }

    "should set play mode repeat" {
      val expectedResponse = SetPlayModeResponse(
          Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("repeat", PlayRepeatState.ON_ALL)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayRepeatState.ON_ALL)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&repeat=on_all$COMMAND_DELIMITER"
    }

    "should set play mode shuffle" {
      val expectedResponse = SetPlayModeResponse(
          Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("shuffle", PlayShuffleState.ON)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setPlayMode("0", PlayShuffleState.ON)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://player/set_play_mode?pid=0&shuffle=on$COMMAND_DELIMITER"
    }

    "should get queue" {
      val expectedResponse = GetQueueResponse(
          Heos(GroupedCommand(PLAYER, GET_QUEUE),
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
          Heos(GroupedCommand(PLAYER, GET_QUEUE),
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
          Heos(GroupedCommand(PLAYER, PLAY_QUEUE),
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
          Heos(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
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
          Heos(GroupedCommand(PLAYER, SAVE_QUEUE),
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
          Heos(GroupedCommand(PLAYER, CLEAR_QUEUE),
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
          Heos(GroupedCommand(PLAYER, PLAY_NEXT),
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
          Heos(GroupedCommand(PLAYER, PLAY_PREVIOUS),
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
          Heos(GroupedCommand(GROUP, GET_GROUPS),
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
          Heos(GroupedCommand(GROUP, GET_GROUP_INFO),
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
          Heos(GroupedCommand(GROUP, SET_GROUP),
              Result.SUCCESS, Message.Builder()
              .add("gid", "0")
              .add("name", "foo")
              .add("pid", "0,1,2")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setGroup("0", listOf("1", "2"))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_group?pid=0,1,2$COMMAND_DELIMITER"
    }

    "should throw if no pid" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setGroup("0", emptyList())
      }
    }

    "should delete group" {
      val expectedResponse = DeleteGroupResponse(
          Heos(GroupedCommand(GROUP, SET_GROUP),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.deleteGroup("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://group/set_group?pid=0$COMMAND_DELIMITER"
    }

    "should get group volume" {
      val expectedResponse = GetVolumeResponse(
          Heos(GroupedCommand(GROUP, GET_VOLUME),
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
          Heos(GroupedCommand(GROUP, SET_VOLUME),
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
          Heos(GroupedCommand(GROUP, VOLUME_UP),
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
          Heos(GroupedCommand(GROUP, VOLUME_UP),
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
          Heos(GroupedCommand(GROUP, VOLUME_DOWN),
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
          Heos(GroupedCommand(GROUP, VOLUME_DOWN),
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
          Heos(GroupedCommand(GROUP, GET_MUTE),
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
          Heos(GroupedCommand(GROUP, SET_MUTE),
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
          Heos(GroupedCommand(GROUP, TOGGLE_MUTE),
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
          Heos(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES),
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
          Heos(GroupedCommand(CommandGroup.BROWSE, GET_SOURCE_INFO),
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
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 2)
              .add("count", 2)
              .build()),
          listOf(
              MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "100"),
              MusicSource("bar", URL("http://example.com"), HEOS_SERVICE, "101")),
          listOf(mapOf("browse" to
              listOf(Option.CREATE_NEW_STATION))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseMusicSources("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse music sources if range is empty" {
      val expectedResponse = BrowseMediaSourcesResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 2)
              .add("count", 2)
              .build()),
          listOf(
              MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "100"),
              MusicSource("bar", URL("http://example.com"), HEOS_SERVICE, "101")),
          listOf(mapOf("browse" to
              listOf(Option.CREATE_NEW_STATION))))

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
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseTopMusic("0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse top music if range is empty" {
      val expectedResponse = BrowseTopMusicResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, MediaType.STATION, "station name",
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
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")),
          listOf(mapOf("browse" to
              listOf(Option.ADD_PLAYLIST_TO_LIBRARY))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.browseSourceContainers("0", "0", IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/browse?sid=0&cid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should browse source containers if range is empty" {
      val expectedResponse = BrowseSourceContainersResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")),
          listOf(mapOf("browse" to
              listOf(Option.ADD_PLAYLIST_TO_LIBRARY))))

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
      val expectedResponse = GetSearchCriteriaResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, GET_SEARCH_CRITERIA),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .build()),
          listOf(
              SearchCriteria("foo", 0, YES),
              SearchCriteria("bar", 1, NO)))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getSearchCriteria("0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/get_search_criteria?sid=0$COMMAND_DELIMITER"
    }

    "should search" {
      val expectedResponse = SearchResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, SEARCH),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("search", "*")
              .add("scid", 0)
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, STATION, "station name",
                  URL("http://example.com"), "5")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.search("0", "*", 0, IntRange(0, 10))

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/search?sid=0&search=*&scid=0&range=0,10$COMMAND_DELIMITER"
    }

    "should search if range is empty" {
      val expectedResponse = SearchResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, SEARCH),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("search", "*")
              .add("scid", 0)
              .add("returned", 6)
              .add("count", 6)
              .build()),
          listOf(
              MediaArtist(YES, NO, MediaType.ARTIST, "artist name",
                  URL("http://example.com"), "0", "0"),
              MediaAlbum(YES, YES, MediaType.ALBUM, "album name",
                  URL("http://example.com"), "0", "0", "1"),
              MediaSong(NO, YES, MediaType.SONG, "song name",
                  URL("http://example.com"), "artist name", "album name", "2"),
              MediaGenre(YES, NO, MediaType.GENRE, "genre name",
                  URL("http://example.com"), "0", "3"),
              MediaContainer(YES, NO, MediaType.CONTAINER, "container name",
                  URL("http://example.com"), "0", "4"),
              MediaStation(NO, YES, MediaType.STATION, "station name",
                  URL("http://example.com"), "5")))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.search("0", "*", 0)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/search?sid=0&search=*&scid=0$COMMAND_DELIMITER"
    }

    "should throw if range start < 0 when searching" {
      shouldThrow<IllegalArgumentException> {
        heosClient.search("0", "*", 0, IntRange(-1, 10))
      }
    }

    "should play stream" {
      val expectedResponse = PlayStreamResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("sid", "0")
              .add("cid", "0")
              .add("mid", "0")
              .add("name", "foo")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playStream("0", "0", "0", "0", "foo")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://browse/play_stream?pid=0&sid=0&cid=0&mid=0&name=foo$COMMAND_DELIMITER"
    }

    "should play input" {
      val expectedResponse = PlayInputResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("mid", "0")
              .add("spid", "0")
              .add("input", "inputs/aux_in_1")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playInput("0", "0", "0", "inputs/aux_in_1")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/play_input?pid=0&mid=0&spid=0&input=inputs/aux_in_1$COMMAND_DELIMITER"
    }

    "should play input from specified input" {
      val expectedResponse = PlayInputResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, PLAY_INPUT),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("input", "inputs/aux_in_1")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.playInput("0", input = "inputs/aux_in_1")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/play_input?pid=0&input=inputs/aux_in_1$COMMAND_DELIMITER"
    }

    "should add container to queue" {
      val expectedResponse = AddToQueueResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("sid", "0")
              .add("cid", "0")
              .add("aid", 3)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.addToQueue("0", "0", "0", AddCriteriaId.ADD_TO_END)

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/add_to_queue?pid=0&sid=0&cid=0&aid=3$COMMAND_DELIMITER"
    }

    "should add track to queue" {
      val expectedResponse = AddToQueueResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, ADD_TO_QUEUE),
              Result.SUCCESS, Message.Builder()
              .add("pid", "0")
              .add("sid", "0")
              .add("cid", "0")
              .add("mid", 0)
              .add("aid", 3)
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.addToQueue("0", "0", "0",
          AddCriteriaId.ADD_TO_END, "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/add_to_queue?pid=0&sid=0&cid=0&mid=0&aid=3$COMMAND_DELIMITER"
    }

    "should rename playlist" {
      val expectedResponse = RenamePlaylistResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, RENAME_PLAYLIST),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("name", "foo")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.renamePlaylist("0", "0", "foo")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/rename_playlist?sid=0&cid=0&name=foo$COMMAND_DELIMITER"
    }

    "should delete playlist" {
      val expectedResponse = DeletePlaylistResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, DELETE_PLAYLIST),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.deletePlaylist("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/delete_playlist?sid=0&cid=0$COMMAND_DELIMITER"
    }

    "should retrieve metadata" {
      val expectedResponse = RetrieveMetadataResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, RETRIEVE_METADATA),
              Result.SUCCESS, Message.Builder()
              .add("sid", "0")
              .add("cid", "0")
              .add("returned", 2)
              .add("count", 2)
              .build()),
          listOf(io.honnix.kheos.common.Metadata("0", listOf(
              Image(URL("http://example.com"), 10.0),
              Image(URL("http://example.com"), 12.0)))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.retrieveMetadata("0", "0")

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/retrieve_metadata?sid=0&cid=0$COMMAND_DELIMITER"
    }

    "should get service options" {
      val expectedResponse = GetServiceOptionsResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, GET_SERVICE_OPTIONS),
              Result.SUCCESS, Message()),
          listOf(mapOf("play" to
              listOf(
                  Option.THUMBS_UP,
                  Option.THUMBS_DOWN))))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.getServiceOptions()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe
          "heos://browse/get_service_options$COMMAND_DELIMITER"
    }

    "should set service option" {
      val expectedResponse = SetServiceOptionResponse(
          Heos(GroupedCommand(CommandGroup.BROWSE, SET_SERVICE_OPTION),
              Result.SUCCESS, Message.Builder()
              .add("option", Option.CREATE_NEW_STATION.id)
              .add("sid", "0")
              .add("name", "foo")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.setServiceOption(Option.CREATE_NEW_STATION,
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
        heosClient.setServiceOption(Option.CREATE_NEW_STATION,
            AttributesBuilder()
                .add("sid", "0")
                .add("name", "foo")
                .build(),
            IntRange(-1, 10))
      }
    }

    "should throw if specify range for option other than ${Option.CREATE_NEW_STATION}" {
      shouldThrow<IllegalArgumentException> {
        heosClient.setServiceOption(Option.ADD_TO_HEOS_FAVORITES,
            AttributesBuilder()
                .add("pid", "0")
                .build(),
            IntRange(0, 10))
      }
    }
  }
}

class HeosChangeEventsClientTest : StringSpec() {
  private val socketExecutorService = QuietDeterministicScheduler()
  private val listenerExecutorService = QuietDeterministicScheduler()
  private val socket = mock<Socket>()
  private val heosChangeEventsClient = HeosChangeEventsClientImpl("localhost", { socket },
      socketExecutorService, listenerExecutorService)

  private fun start(): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
    val expectedResponse = RegisterForChangeEventsResponse(
        Heos(GroupedCommand(SYSTEM, REGISTER_FOR_CHANGE_EVENTS),
            Result.SUCCESS, Message.Builder()
            .add("enable", "on")
            .build()))

    val input = ByteArrayInputStream(JSON.serialize(expectedResponse))
    `when`(socket.getInputStream()).thenReturn(input)

    val output = ByteArrayOutputStream()
    `when`(socket.getOutputStream()).thenReturn(output)

    heosChangeEventsClient.start()
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
      val expectedResponse = RegisterForChangeEventsResponse(
          Heos(GroupedCommand(SYSTEM, REGISTER_FOR_CHANGE_EVENTS),
              Result.FAIL, Message.Builder()
              .add("eid", ErrorId.INTERNAL_ERROR.eid)
              .add("text", "System Internal Error")
              .build()))

      val input = ByteArrayInputStream(JSON.serialize(expectedResponse))
      `when`(socket.getInputStream()).thenReturn(input)

      val output = ByteArrayOutputStream()
      `when`(socket.getOutputStream()).thenReturn(output)

      val exception = shouldThrow<HeosCommandException> {
        heosChangeEventsClient.start()
      }

      exception.eid shouldBe ErrorId.INTERNAL_ERROR
      exception.text shouldBe "System Internal Error"
    }

    "should call listener for event" {
      val listener = mock<ChangeEventListener>()
      heosChangeEventsClient.register(listener)

      start()

      val changeEvent = ChangeEvent(ChangeEventCommand.PLAYER_NOW_PLAYING_PROGRESS,
          Message.Builder()
              .add("pid", "0")
              .add("cur_pos", "0")
              .add("duration", "0")
              .build())
      val changeEventResponse = ChangeEventResponse(changeEvent)

      val input = ByteArrayInputStream(JSON.serialize(changeEventResponse))
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
    override fun onEvent(event: ChangeEvent) {
      println(event)
    }
  })
  client.register(object : ChangeEventListener {
    override fun onEvent(event: ChangeEvent) {
      System.err.println(event)
    }
  })
  client.start()


  Thread.sleep(1000 * 10)

  client.close()
}
