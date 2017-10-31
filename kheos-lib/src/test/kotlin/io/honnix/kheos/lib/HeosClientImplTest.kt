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

import io.kotlintest.TestCaseContext
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.jmock.lib.concurrent.DeterministicScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

class HeosClientImplTest : StringSpec() {
  private lateinit var scheduler: DeterministicScheduler
  private lateinit var heosClient: HeosClient
  private lateinit var socket: Socket

  override val oneInstancePerTest = false

  override fun interceptTestCase(context: TestCaseContext, test: () -> Unit) {
    scheduler = DeterministicScheduler()
    socket = mock<Socket>()
    heosClient = HeosClientImpl("localhost", { socket }, scheduler)

    test()
  }

  init {
    "should schedule heartbeat" {
      val response = HeartbeatResponse(
          Status(GroupedCommand(CommandGroup.SYSTEM, Command.HEART_BEAT),
              Result.SUCCESS, Message(mapOf())))

      val input = ByteArrayInputStream(JSON.serialize(response))
      `when`(socket.getInputStream()).thenReturn(input)

      val output = ByteArrayOutputStream()
      `when`(socket.getOutputStream()).thenReturn(output)

      heosClient.startHeartbeat()
      scheduler.tick(5, TimeUnit.SECONDS)

      input.available() shouldBe 0
    }
  }
}
