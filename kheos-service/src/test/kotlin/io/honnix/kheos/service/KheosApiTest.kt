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
import io.honnix.kheos.common.MediaType.*
import io.honnix.kheos.common.MusicSourceType.*
import io.honnix.kheos.common.YesNo.*
import io.honnix.kheos.lib.*
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.*
import io.kotlintest.properties.*
import io.kotlintest.specs.StringSpec
import okio.ByteString
import org.hamcrest.MatcherAssert.assertThat
import org.mockito.Mockito.verify
import java.net.URL
import java.util.concurrent.*

internal class ApiTest : StringSpec() {
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

internal class KheosApiKtTest : StringSpec() {
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

internal class HeosSystemCommandResourceTest : StringSpec() {
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

internal class HeosPlayerCommandResourceTest : StringSpec() {
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
                    "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE,
                    "ADAG0000"),
                Player("name1", "1", "model1",
                    "0.1", "192.168.1.101", "wifi", Lineout.FIXED,
                    "ADAG0000", "100", Control.NETWORK)))

        `when`(heosClient.getPlayers()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "")))
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
                "0.0", "192.168.1.100", "wifi", Lineout.VARIABLE, "ADAG0000"))

        `when`(heosClient.getPlayerInfo("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/0")))
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
            serviceHelper.request("GET", path(version, basePath, "/0/state")))
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
                "/0/state?state=play")))
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
                path(version, basePath, "/0/state")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if invalid state" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/state?state=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get now playing media" {
      forAll(allVersions()) { version ->
        val payload = GetNowPlayingMediaResponse(
            Heos(GroupedCommand(PLAYER, GET_NOW_PLAYING_MEDIA),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()),
            NowPlayingMedia(STATION, "song", "album", "artist",
                URL("http://example.com"), "0", "0", "0", "0",
                "station"),
            listOf(mapOf("play" to
                listOf(Option.ADD_TO_HEOS_FAVORITES))))

        `when`(heosClient.getNowPlayingMedia("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/now_playing_media")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetNowPlayingMediaResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should get volume" {
      forAll(allVersions()) { version ->
        val payload = GetVolumeResponse(
            Heos(GroupedCommand(PLAYER, GET_VOLUME),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("level", "10")
                .build()))

        `when`(heosClient.getVolume(PLAYER, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/volume")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetVolumeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set volume" {
      forAll(allVersions()) { version ->
        val payload = SetVolumeResponse(
            Heos(GroupedCommand(PLAYER, SET_VOLUME),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("level", "10")
                .build()))

        `when`(heosClient.setVolume(PLAYER, "0", 10)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/volume?level=10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetVolumeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if no level" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/volume")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if level is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/volume?level=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should volume up" {
      forAll(allVersions()) { version ->
        val payload = VolumeUpResponse(
            Heos(GroupedCommand(PLAYER, VOLUME_UP),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "3")
                .build()))

        `when`(heosClient.volumeUp(PLAYER, "0", 3)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/up?step=3")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeUpResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should volume up with default step" {
      forAll(allVersions()) { version ->
        val payload = VolumeUpResponse(
            Heos(GroupedCommand(PLAYER, VOLUME_UP),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "5")
                .build()))

        `when`(heosClient.volumeUp(PLAYER, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/up")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeUpResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if step is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/volume/up?step=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should volume down" {
      forAll(allVersions()) { version ->
        val payload = VolumeDownResponse(
            Heos(GroupedCommand(PLAYER, VOLUME_DOWN),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "3")
                .build()))

        `when`(heosClient.volumeDown(PLAYER, "0", 3)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/down?step=3")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeDownResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should volume down with default step" {
      forAll(allVersions()) { version ->
        val payload = VolumeDownResponse(
            Heos(GroupedCommand(PLAYER, VOLUME_DOWN),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "5")
                .build()))

        `when`(heosClient.volumeDown(PLAYER, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/down")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeDownResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if step is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/volume/down?step=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get mute" {
      forAll(allVersions()) { version ->
        val payload = GetMuteResponse(
            Heos(GroupedCommand(PLAYER, GET_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.getMute(PLAYER, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/mute")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set mute" {
      forAll(allVersions()) { version ->
        val payload = SetMuteResponse(
            Heos(GroupedCommand(PLAYER, SET_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("state", MuteState.OFF)
                .build()))

        `when`(heosClient.setMute(PLAYER, "0", MuteState.OFF)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/mute?state=off")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should toggle mute if no state" {
      forAll(allVersions()) { version ->
        val payload = ToggleMuteResponse(
            Heos(GroupedCommand(PLAYER, TOGGLE_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.toggleMute(PLAYER, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath, "/0/mute")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<ToggleMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if invalid state" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/mute?state=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get play mode" {
      forAll(allVersions()) { version ->
        val payload = GetPlayModeResponse(
            Heos(GroupedCommand(PLAYER, GET_PLAY_MODE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("repeat", PlayRepeatState.OFF)
                .add("shuffle", PlayShuffleState.OFF)
                .build()))

        `when`(heosClient.getPlayMode("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/mode")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetPlayModeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set play mode" {
      forAll(allVersions()) { version ->
        val payload = SetPlayModeResponse(
            Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("repeat", PlayRepeatState.ON_ALL)
                .add("shuffle", PlayShuffleState.OFF)
                .build()))

        `when`(heosClient.setPlayMode("0", PlayRepeatState.ON_ALL, PlayShuffleState.OFF))
            .thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/mode?repeat=on_all&shuffle=off")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetPlayModeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set play mode repeat" {
      forAll(allVersions()) { version ->
        val payload = SetPlayModeResponse(
            Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("repeat", PlayRepeatState.ON_ALL)
                .build()))

        `when`(heosClient.setPlayMode("0", PlayRepeatState.ON_ALL)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/mode?repeat=on_all")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetPlayModeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set play mode shuffle" {
      forAll(allVersions()) { version ->
        val payload = SetPlayModeResponse(
            Heos(GroupedCommand(PLAYER, SET_PLAY_MODE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("shuffle", PlayShuffleState.ON)
                .build()))

        `when`(heosClient.setPlayMode("0", PlayShuffleState.ON))
            .thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath, "/0/mode?shuffle=on")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetPlayModeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if missing both states" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/mode")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get queue" {
      forAll(allVersions()) { version ->
        val payload = GetQueueResponse(
            Heos(GroupedCommand(PLAYER, GET_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()),
            listOf(QueueItem("song", "album", "artist",
                URL("http://example.com"), "0", "0", "0")))

        `when`(heosClient.getQueue("0", IntRange(0, 0))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/0/queue?range=0,0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should get queue with default range" {
      forAll(allVersions()) { version ->
        val payload = GetQueueResponse(
            Heos(GroupedCommand(PLAYER, GET_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()),
            listOf(QueueItem("song", "album", "artist",
                URL("http://example.com"), "0", "0", "0")))

        `when`(heosClient.getQueue("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/0/queue")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if invalid range" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, basePath, "/0/queue?range=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should play queue" {
      forAll(allVersions()) { version ->
        val payload = PlayQueueResponse(
            Heos(GroupedCommand(PLAYER, PLAY_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("qid", "0")
                .build()))

        `when`(heosClient.playQueue("0", "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath, "/0/queue/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<PlayQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should remove a single item from queue" {
      forAll(allVersions()) { version ->
        val payload = RemoveFromQueueResponse(
            Heos(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("qid", "0")
                .build()))

        `when`(heosClient.removeFromQueue("0", listOf("0"))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("DELETE", path(version, basePath, "/0/queue/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<RemoveFromQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should remove multiple items from queue" {
      forAll(allVersions()) { version ->
        val payload = RemoveFromQueueResponse(
            Heos(GroupedCommand(PLAYER, REMOVE_FROM_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("qid", "0,1")
                .build()))

        `when`(heosClient.removeFromQueue("0", listOf("0", "1"))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("DELETE", path(version, basePath, "/0/queue?qid=0&qid=1")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<RemoveFromQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should clear queue" {
      forAll(allVersions()) { version ->
        val payload = ClearQueueResponse(
            Heos(GroupedCommand(PLAYER, CLEAR_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.clearQueue("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("DELETE", path(version, basePath, "/0/queue")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<ClearQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should save queue" {
      forAll(allVersions()) { version ->
        val payload = SaveQueueResponse(
            Heos(GroupedCommand(PLAYER, SAVE_QUEUE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("name", "foo bar")
                .build()))

        `when`(heosClient.saveQueue("0", "foo bar")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath, "/0/queue?name=foo%20bar")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SaveQueueResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if missing name" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/queue")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should play next" {
      forAll(allVersions()) { version ->
        val payload = PlayNextResponse(
            Heos(GroupedCommand(PLAYER, PLAY_NEXT),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.playNext("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath, "/0/play/next")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<PlayNextResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should play previous" {
      forAll(allVersions()) { version ->
        val payload = PlayPreviousResponse(
            Heos(GroupedCommand(PLAYER, PLAY_PREVIOUS),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.playPrevious("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath, "/0/play/previous")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<PlayPreviousResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should play stream" {
      forAll(allVersions()) { version ->
        val payload = PlayStreamResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, PLAY_STREAM),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("sid", "0")
                .add("cid", "0")
                .add("mid", "0")
                .add("name", "foo")
                .build()))

        `when`(heosClient.playStream("0", "0", "0", "0", "foo"))
            .thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/play/stream/0/0/0?name=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<PlayStreamResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if missing name" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/play/stream/0/0/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }
  }
}

internal class HeosGroupCommandResourceTest : StringSpec() {
  private val serviceHelper = ServiceHelper.create({ init(it) }, "kheos-service-test")

  private val basePath = "/groups"

  private val heosClient = mock<HeosClient>()

  private fun init(environment: Environment) {
    environment.routingEngine()
        .registerRoutes(HeosGroupCommandResource(heosClient).routes().stream())
  }

  init {
    serviceHelper.start()
    autoClose(serviceHelper)

    "should get groups" {
      forAll(allVersions()) { version ->
        val payload = GetGroupsResponse(
            Heos(GroupedCommand(GROUP, GET_GROUPS),
                Result.SUCCESS, Message()),
            listOf(
                Group("foo", "0",
                    listOf(GroupedPlayer("foofoo", "0", Role.LEADER),
                        GroupedPlayer("foobar", "1", Role.MEMBER))),
                Group("bar", "1",
                    listOf(GroupedPlayer("barbar", "1", Role.LEADER)))))

        `when`(heosClient.getGroups()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetGroupsResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should get group info" {
      forAll(allVersions()) { version ->
        val payload = GetGroupInfoResponse(
            Heos(GroupedCommand(GROUP, GET_GROUP_INFO),
                Result.SUCCESS, Message.Builder()
                .add("gid", "0")
                .build()),
            Group("foo", "0",
                listOf(GroupedPlayer("foofoo", "0", Role.LEADER),
                    GroupedPlayer("foobar", "1", Role.MEMBER))))

        `when`(heosClient.getGroupInfo("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath, "/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetGroupInfoResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should set group" {
      forAll(allVersions()) { version ->
        val payload = SetGroupResponse(
            Heos(GroupedCommand(GROUP, SET_GROUP),
                Result.SUCCESS, Message.Builder()
                .add("gid", "0")
                .add("name", "foo")
                .add("pid", "0,1,2")
                .build()))

        `when`(heosClient.setGroup("0", listOf("1", "2"))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "?leader_id=0&member_ids=1,2")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetGroupResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if no leader_id" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "?member_ids=1,2")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if no member_ids" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "?leader_id=0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if no leader_id and member_ids" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should delete group" {
      forAll(allVersions()) { version ->
        val payload = DeleteGroupResponse(
            Heos(GroupedCommand(GROUP, SET_GROUP),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.deleteGroup("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("DELETE", path(version, basePath,
                "?leader_id=0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<DeleteGroupResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if no leader_id" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("DELETE",
                path(version, basePath, "")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get volume" {
      forAll(allVersions()) { version ->
        val payload = GetVolumeResponse(
            Heos(GroupedCommand(GROUP, GET_VOLUME),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("level", "10")
                .build()))

        `when`(heosClient.getVolume(GROUP, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/volume")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetVolumeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set volume" {
      forAll(allVersions()) { version ->
        val payload = SetVolumeResponse(
            Heos(GroupedCommand(GROUP, SET_VOLUME),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("level", "10")
                .build()))

        `when`(heosClient.setVolume(GROUP, "0", 10)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/volume?level=10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetVolumeResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if no level" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/volume")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if level is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/volume?level=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should volume up" {
      forAll(allVersions()) { version ->
        val payload = VolumeUpResponse(
            Heos(GroupedCommand(GROUP, VOLUME_UP),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "3")
                .build()))

        `when`(heosClient.volumeUp(GROUP, "0", 3)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/up?step=3")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeUpResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should volume up with default step" {
      forAll(allVersions()) { version ->
        val payload = VolumeUpResponse(
            Heos(GroupedCommand(GROUP, VOLUME_UP),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "5")
                .build()))

        `when`(heosClient.volumeUp(GROUP, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/up")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeUpResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if step is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/volume/up?step=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should volume down" {
      forAll(allVersions()) { version ->
        val payload = VolumeDownResponse(
            Heos(GroupedCommand(GROUP, VOLUME_DOWN),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "3")
                .build()))

        `when`(heosClient.volumeDown(GROUP, "0", 3)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/down?step=3")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeDownResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should volume down with default step" {
      forAll(allVersions()) { version ->
        val payload = VolumeDownResponse(
            Heos(GroupedCommand(GROUP, VOLUME_DOWN),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("step", "5")
                .build()))

        `when`(heosClient.volumeDown(GROUP, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("POST", path(version, basePath,
                "/0/volume/down")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<VolumeDownResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if step is not an integer" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("POST",
                path(version, basePath, "/0/volume/down?step=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get mute" {
      forAll(allVersions()) { version ->
        val payload = GetMuteResponse(
            Heos(GroupedCommand(GROUP, GET_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.getMute(GROUP, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/0/mute")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should set mute" {
      forAll(allVersions()) { version ->
        val payload = SetMuteResponse(
            Heos(GroupedCommand(GROUP, SET_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .add("state", MuteState.OFF)
                .build()))

        `when`(heosClient.setMute(GROUP, "0", MuteState.OFF)).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath,
                "/0/mute?state=off")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SetMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should toggle mute if no state" {
      forAll(allVersions()) { version ->
        val payload = ToggleMuteResponse(
            Heos(GroupedCommand(GROUP, TOGGLE_MUTE),
                Result.SUCCESS, Message.Builder()
                .add("pid", "0")
                .build()))

        `when`(heosClient.toggleMute(GROUP, "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("PATCH", path(version, basePath, "/0/mute")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<ToggleMuteResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if invalid state" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("PATCH",
                path(version, basePath, "/0/mute?state=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }
  }
}

internal class HeosBrowseCommandResourceTest : StringSpec() {
  private val serviceHelper = ServiceHelper.create({ init(it) }, "kheos-service-test")

  private val basePath = "/browse"

  private val heosClient = mock<HeosClient>()

  private fun init(environment: Environment) {
    environment.routingEngine()
        .registerRoutes(HeosBrowseCommandResource(heosClient).routes().stream())
  }

  init {
    serviceHelper.start()
    autoClose(serviceHelper)

    "should get music sources" {
      forAll(allVersions()) { version ->
        val payload = GetMusicSourcesResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, GET_MUSIC_SOURCES),
                Result.SUCCESS, Message()),
            listOf(
                MusicSource("foo", URL("http://example.com"), HEOS_SERVER, "0"),
                MusicSource("bar", URL("http://example.com"), DLNA_SERVER, "1")))

        `when`(heosClient.getMusicSources()).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, "", "/music_sources")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetMusicSourcesResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should get music source info" {
      forAll(allVersions()) { version ->
        val payload = GetMusicSourceInfoResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, GET_SOURCE_INFO),
                Result.SUCCESS, Message()),
            MusicSource("bar", URL("http://example.com"), DLNA_SERVER, "0"))

        `when`(heosClient.getMusicSourceInfo("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, "", "/music_sources/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetMusicSourceInfoResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should browse media sources" {
      forAll(allVersions()) { version ->
        val payload = BrowseMediaSourcesResponse(
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

        `when`(heosClient.browseMediaSources("0", IntRange(0, 10))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/media_sources/0?range=0,10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseMediaSourcesResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should browse media sources with default range" {
      forAll(allVersions()) { version ->
        val payload = BrowseMediaSourcesResponse(
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

        `when`(heosClient.browseMediaSources("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/media_sources/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseMediaSourcesResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if invalid range" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, basePath, "/media_sources/0?range=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should browse top music" {
      forAll(allVersions()) { version ->
        val payload = BrowseTopMusicResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")))

        `when`(heosClient.browseTopMusic("0", IntRange(0, 10))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/top_music/0?range=0,10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseTopMusicResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should browse top music with default range" {
      forAll(allVersions()) { version ->
        val payload = BrowseTopMusicResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")))

        `when`(heosClient.browseTopMusic("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/top_music/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseTopMusicResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if invalid range" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, basePath, "/top_music/0?range=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should browse source containers" {
      forAll(allVersions()) { version ->
        val payload = BrowseSourceContainersResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("cid", "0")
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")),
            listOf(mapOf("browse" to
                listOf(Option.ADD_PLAYLIST_TO_LIBRARY))))

        `when`(heosClient.browseSourceContainers("0", "0", IntRange(0, 10))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/source_containers/0/0?range=0,10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseSourceContainersResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should browse source containers with default range" {
      forAll(allVersions()) { version ->
        val payload = BrowseSourceContainersResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, Command.BROWSE),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("cid", "0")
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")),
            listOf(mapOf("browse" to
                listOf(Option.ADD_PLAYLIST_TO_LIBRARY))))

        `when`(heosClient.browseSourceContainers("0", "0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, basePath,
                "/source_containers/0/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<BrowseSourceContainersResponse>(
            response.payload().get().toByteArray()) shouldBe payload
      }
    }

    "should return client error if invalid range" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, basePath, "/source_containers/0/0?range=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should get search criteria" {
      forAll(allVersions()) { version ->
        val payload = GetSearchCriteriaResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, GET_SEARCH_CRITERIA),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .build()),
            listOf(
                SearchCriteria("foo", 0, YES),
                SearchCriteria("bar", 1, NO)))

        `when`(heosClient.getSearchCriteria("0")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, "", "/search_criteria/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<GetSearchCriteriaResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should search" {
      forAll(allVersions()) { version ->
        val payload = SearchResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, SEARCH),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("search", "*")
                .add("scid", 0)
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")))

        `when`(heosClient.search("0", 0, "*", IntRange(0, 10))).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, "",
                "/search/0/0?search_string=*&range=0,10")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SearchResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should search if no range" {
      forAll(allVersions()) { version ->
        val payload = SearchResponse(
            Heos(GroupedCommand(CommandGroup.BROWSE, SEARCH),
                Result.SUCCESS, Message.Builder()
                .add("sid", "0")
                .add("search", "*")
                .add("scid", 0)
                .add("returned", 6)
                .add("count", 6)
                .build()),
            listOf(
                MediaArtist(YES, NO, ARTIST, "artist name",
                    URL("http://example.com"), "0", "0"),
                MediaAlbum(YES, YES, ALBUM, "album name",
                    URL("http://example.com"), "0", "0", "1"),
                MediaSong(NO, YES, SONG, "song name",
                    URL("http://example.com"), "artist name", "album name", "2"),
                MediaGenre(YES, NO, GENRE, "genre name",
                    URL("http://example.com"), "0", "3"),
                MediaContainer(YES, NO, CONTAINER, "container name",
                    URL("http://example.com"), "0", "4"),
                MediaStation(NO, YES, STATION, "station name",
                    URL("http://example.com"), "5")))

        `when`(heosClient.search("0", 0, "*")).thenReturn(payload)
        val response = awaitResponse(
            serviceHelper.request("GET", path(version, "",
                "/search/0/0?search_string=*")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.SUCCESSFUL)))
        response.payload().isPresent shouldBe true
        JSON.deserialize<SearchResponse>(response.payload().get().toByteArray()) shouldBe
            payload
      }
    }

    "should return client error if invalid scid" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, "", "/search/0/foo?search_string=*")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if invalid range" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, "", "/search/0/0?search_string=*&range=foo")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }

    "should return client error if missing search_string" {
      forAll(allVersions()) { version ->
        val response = awaitResponse(
            serviceHelper.request("GET",
                path(version, "", "/search/0/0")))
        assertThat(response, hasStatus(belongsToFamily(StatusType.Family.CLIENT_ERROR)))
      }
    }
  }
}
