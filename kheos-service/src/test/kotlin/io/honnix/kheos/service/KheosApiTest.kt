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

import com.spotify.apollo.*
import com.spotify.apollo.test.ServiceHelper
import com.spotify.apollo.test.unit.ResponseMatchers.hasStatus
import com.spotify.apollo.test.unit.StatusTypeMatchers.belongsToFamily
import io.honnix.kheos.common.*
import io.honnix.kheos.common.Command.*
import io.honnix.kheos.common.CommandGroup.*
import io.honnix.kheos.lib.*
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.*
import io.kotlintest.properties.*
import io.kotlintest.specs.StringSpec
import okio.ByteString
import org.hamcrest.MatcherAssert.assertThat
import org.mockito.Mockito.verify
import java.util.concurrent.*

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
          Heos(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
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
            Heos(GroupedCommand(SYSTEM, CHECK_ACCOUNT),
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

    "should sign in" {
      forAll(allVersions()) { version ->
        val payload = SignInResponse(
            Heos(GroupedCommand(SYSTEM, SIGN_IN),
                Result.SUCCESS, Message.Builder()
                .add("signed_in")
                .add("un", "user@example.com")
                .build()))
        `when`(heosClient.signIn("foo", "bar")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/account?user_name=foo&password=bar")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SignInResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if no user_name" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/account?password=bar")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if no password" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/account?user_name=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if no user_name nor password" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/account")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should sign out" {
      forAll(allVersions()) { version ->
        val payload = SignOutResponse(
            Heos(GroupedCommand(SYSTEM, SIGN_OUT),
                Result.SUCCESS, Message.Builder()
                .add("signed_out")
                .build()))
        `when`(heosClient.signOut()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("DELETE",
                path(version, basePath, "/account")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SignOutResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should reboot" {
      forAll(allVersions()) { version ->
        val payload = RebootResponse(
            Heos(GroupedCommand(SYSTEM, REBOOT),
                Result.SUCCESS, Message()))
        `when`(heosClient.reboot()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PUT",
                path(version, basePath, "/state")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<RebootResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }
  }
}

class HeosPlayerCommandResourceTest : StringSpec() {
  private val serviceHelper = ServiceHelper.create({ init(it) }, "kheos-service-test")

  private val basePath = "/players"

  private val heosClient = mock<HeosClient>()

  private fun init(environment: Environment) {
    environment.routingEngine()
        .registerRoutes(HeosPlayerCommandResource(heosClient).routes().stream())
  }

  init {
    serviceHelper.start()
    autoClose(serviceHelper)

    "should get players" {
      forAll(allVersions()) { version ->
        val payload = GetPlayersResponse(
            Heos(GroupedCommand(PLAYER, GET_PLAYERS),
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

    "should get player info" {
      forAll(allVersions()) { version ->
        val payload = GetPlayerInfoResponse(
            Heos(GroupedCommand(PLAYER, GET_PLAYER_INFO),
                Result.SUCCESS, Message()),
            Player("name0", "0", "model0",
                "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE))

        `when`(heosClient.getPlayerInfo("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/players/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetPlayerInfoResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should get play state" {
      forAll(allVersions()) { version ->
        val payload = GetPlayStateResponse(
            Heos(GroupedCommand(PLAYER, GET_PLAY_STATE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("state", "play")
                .build()))

        `when`(heosClient.getPlayState("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/players/0/state")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetPlayStateResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should set play state" {
      forAll(allVersions()) { version ->
        val payload = SetPlayStateResponse(
            Heos(GroupedCommand(PLAYER, SET_PLAY_STATE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("state", "play")
                .build()))

        `when`(heosClient.setPlayState("0", PlayState.PLAY)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/players/0/state?state=play")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetPlayStateResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if no state" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/players/0/state")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if invalid state" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/players/0/state?state=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }
  }
}
