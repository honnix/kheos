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
package io.honnix.kheos.service

import com.spotify.apollo.Environment
import com.spotify.apollo.Response
import com.spotify.apollo.StatusType
import com.spotify.apollo.test.ServiceHelper
import com.spotify.apollo.test.unit.ResponseMatchers.hasStatus
import com.spotify.apollo.test.unit.StatusTypeMatchers.belongsToFamily
import io.honnix.kheos.lib.CheckAccountResponse
import io.honnix.kheos.lib.Command
import io.honnix.kheos.lib.CommandGroup
import io.honnix.kheos.lib.Control
import io.honnix.kheos.lib.ErrorId
import io.honnix.kheos.lib.GetPlayersResponse
import io.honnix.kheos.lib.GroupedCommand
import io.honnix.kheos.lib.HeosClient
import io.honnix.kheos.lib.HeosClientException
import io.honnix.kheos.lib.HeosCommandException
import io.honnix.kheos.lib.JSON
import io.honnix.kheos.lib.Lineout
import io.honnix.kheos.lib.Message
import io.honnix.kheos.lib.Player
import io.honnix.kheos.lib.Result
import io.honnix.kheos.lib.Status
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.properties.forAll
import io.kotlintest.properties.headers
import io.kotlintest.properties.row
import io.kotlintest.properties.table
import io.kotlintest.specs.StringSpec
import okio.ByteString
import org.hamcrest.MatcherAssert.assertThat
import org.mockito.Mockito.verify
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

class ApiTest : StringSpec() {
  init {
    "should add correct prefix" {
      val route1 = mock<KRoute>()
      val route2 = mock<KRoute>()

      Api.prefixRoutes(listOf(route1, route2), Api.Version.V0).size shouldBe 2
      verify(route1).withPrefix("/api/v0")
      verify(route2).withPrefix("/api/v0")
    }
  }
}

class KheosApiKtTest : StringSpec() {
  init {
    "should call and build success response" {
      val payload = CheckAccountResponse(
          Status(GroupedCommand(CommandGroup.SYSTEM, Command.CHECK_ACCOUNT),
              Result.SUCCESS, Message()))
      val response = callAndBuildResponse {
        payload
      }
      assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
      response.payload().get() shouldBe payload
    }

    "should call and build error response" {
      val response = callAndBuildResponse {
        throw HeosCommandException(ErrorId.INTERNAL_ERROR, "Internal error")
      }
      assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SERVER_ERROR)))
    }

    "should call and reconnect in case of HeosClientException" {
      val response = callAndBuildResponse {
        throw HeosClientException("forced failure")
      }
      assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SERVER_ERROR)))
    }
  }
}

internal fun awaitResponse(completionStage: CompletionStage<Response<ByteString>>) =
    completionStage.toCompletableFuture().get(5, TimeUnit.SECONDS)

internal fun path(version: Api.Version, basePath: String, path: String) =
    version.prefix() + basePath + path

internal fun allVersions() = table(
    headers("version"),
    row(Api.Version.V0)
)

class HeosSystemCommandResourceTest : StringSpec() {
  private val serviceHelper = ServiceHelper.create({ init(it) }, "kheos-service-test")

  private val basePath = "/system"

  private val heosClient = mock<HeosClient>()

  private fun init(environment: Environment) {
    environment.routingEngine()
        .registerRoutes(HeosSystemCommandResource(heosClient).routes().stream())
  }

  init {
    serviceHelper.start()
    autoClose(serviceHelper)

    "should check account" {
      forAll(allVersions()) { version ->
        val payload = CheckAccountResponse(
            Status(GroupedCommand(CommandGroup.SYSTEM, Command.CHECK_ACCOUNT),
                Result.SUCCESS, Message()))
        `when`(heosClient.checkAccount()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/account")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<CheckAccountResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should get players" {
      forAll(allVersions()) { version ->
        val payload = GetPlayersResponse(
            Status(GroupedCommand(CommandGroup.PLAYER, Command.GET_PLAYERS),
                Result.SUCCESS, Message()),
            listOf(
                Player("name0", "0", "model0",
                    "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE),
                Player("name1", "1", "model1",
                    "0.1", "192.168.1.101", "wifi", Lineout.FIXED,
                    "100", Control.NETWORK)))

        `when`(heosClient.getPlayers()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/players")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetPlayersResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }
  }
}
