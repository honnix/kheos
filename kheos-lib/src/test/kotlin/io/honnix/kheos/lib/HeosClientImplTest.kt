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

import io.honnix.kheos.lib.Command.CHECK_ACCOUNT
import io.honnix.kheos.lib.Command.HEART_BEAT
import io.honnix.kheos.lib.Command.SIGN_IN
import io.honnix.kheos.lib.Command.SIGN_OUT
import io.honnix.kheos.lib.CommandGroup.SYSTEM
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
    "should schedule heartbeat" {
      val response = HeartbeatResponse(
          Status(GroupedCommand(SYSTEM, HEART_BEAT),
              Result.SUCCESS, Message(mapOf())))

      val (input, output) = prepareInputOutput(response)

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)

      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/heart_beat\r\n"

      heosClient.stopHeartbeat()
      scheduler.isShutdown shouldBe true
    }

    "should successfully check account" {
      val expectedResponse = CheckAccountResponse(
          Status(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
              Result.SUCCESS, Message()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.checkAccount()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/check_account\r\n"
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
      output.toString() shouldBe "heos://system/check_account\r\n"
    }

    "should successfully sign in" {
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
      output.toString() shouldBe "heos://system/sign_in?un=user@example.com&pw=bar\r\n"
    }

    "should successfully sign out" {
      val expectedResponse = SignOutResponse(
          Status(GroupedCommand(SYSTEM, SIGN_OUT),
              Result.SUCCESS, Message.Builder()
              .add("signed_out")
              .build()))

      val (input, output) = prepareInputOutput(expectedResponse)

      val actualResponse = heosClient.signOut()

      actualResponse shouldBe expectedResponse
      input.available() shouldBe 0
      output.toString() shouldBe "heos://system/sign_out\r\n"
    }
  }
}
