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
  fun checkAccount(): CheckAccountResponse

  companion object {
    fun newInstance(host: String): HeosClient {
      return HeosClientImpl(host)
    }
  }
}

private class HeosClientImpl(host: String,
                             socketFactory: () -> Socket = { Socket(host, HEOS_PORT) },
                             heartbeatExecutorService: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) : HeosClient {
  private val logger = LoggerFactory.getLogger(HeosClientImpl::class.java)
  private val clientSocket = socketFactory()
  private val output = PrintWriter(clientSocket.getOutputStream(), true)
  private val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

  init {
    heartbeatExecutorService.scheduleAtFixedRate({
      try {
        logger.info("sending heartbeat command")
        sendCommand(HeartbeatResponse::class.java, GroupedCommand(SYSTEM, HEART_BEAT))
      } catch (e: HeosCommandException) {
        logger.warn("heartbeat command got a failure status", e)
      } catch (e: Exception) {
        logger.error("failure happened for heartbeat command", e)
      }
    }, 5, 30, TimeUnit.SECONDS)

    Runtime.getRuntime().addShutdownHook(Thread({
      heartbeatExecutorService.shutdownNow()
    }))
  }

  private fun <T : GenericResponse> sendCommand(responseType: Class<T>,
                                                command: GroupedCommand,
                                                attributes: Map<String, String> = emptyMap()): T {
    output.printf("${mkCommand(command, attributes)}\r\n")
    val rawResponse = input.readLine()
    logger.debug(rawResponse)
    val response = JSON.mapper.readValue(rawResponse, responseType)

    if (response.status.result == Result.FAIL) {
      throw HeosCommandException.build(response.status.message)
    }
    return response
  }

  private fun mkCommand(command: GroupedCommand, attributes: Map<String, String>): String {
    val attributesStr = if (attributes.isNotEmpty())
      "?${attributes.map { (k, v) -> "$k=$v" }.joinToString(separator = "&")}"
    else ""
    return "heos:///${command.group.group}/${command.command}$attributesStr"
  }

  override fun checkAccount() =
      sendCommand(CheckAccountResponse::class.java, GroupedCommand(SYSTEM, CHECK_ACCOUNT))
}

fun main(args: Array<String>) {
  val c = HeosClient.newInstance("192.168.17.219")
  println(c.checkAccount())
}
