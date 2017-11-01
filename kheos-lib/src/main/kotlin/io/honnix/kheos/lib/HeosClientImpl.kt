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
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val HEOS_PORT = 1255

interface HeosClient {
  fun startHeartbeat()

  fun stopHeartbeat()

  fun heartbeat(): HeartbeatResponse

  fun checkAccount(): CheckAccountResponse

  fun signIn(userName: String, password: String): SignInResponse

  fun signOut(): SignOutResponse

  companion object {
    fun newInstance(host: String): HeosClient {
      return HeosClientImpl(host)
    }
  }
}

internal class HeosClientImpl(host: String,
                              socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                              private val heartbeatExecutorService: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) : HeosClient {
  private val logger = LoggerFactory.getLogger(HeosClientImpl::class.java)

  private val clientSocket by lazy(socketFactory)
  private val output by lazy { PrintWriter(clientSocket.getOutputStream(), true) }
  private val input by lazy { BufferedReader(InputStreamReader(clientSocket.getInputStream())) }

  private inline fun <reified T : GenericResponse> sendCommand(command: GroupedCommand,
                                                               attributes: Attributes = Attributes(mapOf())): T {
    output.printf("${mkCommand(command, attributes)}\r\n")
    val rawResponse = input.readLine()
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

  override fun startHeartbeat() {
    heartbeatExecutorService.scheduleWithFixedDelay({
      try {
        logger.info("sending heartbeat command")
        val response: HeartbeatResponse = heartbeat()
        logger.debug("received heartbeat response {}", response)
      } catch (e: HeosCommandException) {
        logger.warn("heartbeat command got a fail status: eid({}) text({})", e.eid, e.text, e)
      } catch (e: Exception) {
        logger.error("unknown failure happened", e)
      }
    }, 0, 30, TimeUnit.SECONDS)
  }

  override fun stopHeartbeat() {
    heartbeatExecutorService.shutdownNow()
    clientSocket.close()
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
}
