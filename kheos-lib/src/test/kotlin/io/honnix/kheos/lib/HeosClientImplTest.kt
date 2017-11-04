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
import io.kotlintest.TestCaseContext
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.jmock.lib.concurrent.DeterministicScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
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

  private lateinit var scheduler: DeterministicScheduler
  private lateinit var heosClient: HeosClient
  private lateinit var socket: Socket

  override val oneInstancePerTest = false

  private fun prepareInputOutput(response: GenericResponse): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
    val input = ByteArrayInputStream(JSON.serialize(response))
    `when`(socket.getInputStream()).thenReturn(input)

    val output = ByteArrayOutputStream()
    `when`(socket.getOutputStream()).thenReturn(output)

    return Pair(input, output)
  }

  override fun interceptTestCase(context: TestCaseContext, test: () -> Unit) {
    scheduler = QuietDeterministicScheduler()
    socket = mock<Socket>()
    heosClient = HeosClientImpl("localhost", { socket }, scheduler)

    test()
  }

  init {
    "should create an instance of HeosClientImpl" {
      HeosClient.newInstance("localhost")::class shouldBe HeosClientImpl::class
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
              .add("eid", ErrorCode.INTERNAL_ERROR.ordinal.toString())
              .add("text", "System Internal Error")
              .build()))

      val (input, output) = prepareInputOutput(response)

      val exception = shouldThrow<HeosCommandException> {
        heosClient.checkAccount()
      }
      exception.eid shouldBe ErrorCode.INTERNAL_ERROR.ordinal
      exception.text shouldBe "System Internal Error"

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account$COMMAND_DELIMITER"
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
  }
}
