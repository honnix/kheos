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

import io.grpc.stub.StreamObserver
import io.honnix.kheos.common.AttributesBuilder
import io.honnix.kheos.common.CommandGroup
import io.honnix.kheos.common.CommandGroup.GROUP
import io.honnix.kheos.common.CommandGroup.PLAYER
import io.honnix.kheos.lib.ErrorId
import io.honnix.kheos.lib.HeosClient
import io.honnix.kheos.lib.HeosClientException
import io.honnix.kheos.lib.HeosCommandException
import io.honnix.kheos.proto.base.v1.*
import io.honnix.kheos.proto.browse.v1.*
import io.honnix.kheos.proto.group.v1.*
import io.honnix.kheos.proto.player.v1.*
import io.honnix.kheos.proto.system.v1.*
import io.kotlintest.Spec
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import java.util.concurrent.Executors

internal class KheosApiKtTest : StringSpec() {
  private val captor = ArgumentCaptor.forClass(io.grpc.StatusRuntimeException::class.java)

  private val executor = Executors.newSingleThreadExecutor()

  override fun interceptSpec(context: Spec, spec: () -> Unit) {
    super.interceptSpec(context, spec)
    executor.shutdownNow()
  }

  init {
    "should callAndObserve and build success response" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      val payload = CheckAccountResponse.getDefaultInstance()
      callAndObserve(executor, responseObserver) {
        payload
      }
      verify(responseObserver, timeout(1000)).onNext(payload)
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should callAndObserve and build error response in case of IllegalArgumentException" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      val exception = IllegalArgumentException("Illegal argument")
      callAndObserve(executor, responseObserver) {
        throw exception
      }
      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
      captor.value.status.cause shouldBe exception
      captor.value.status.description shouldBe "Illegal argument"
    }

    "should callAndObserve and build error response in case of HeosCommandException" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      val exception = HeosCommandException(ErrorId.INTERNAL_ERROR, "Internal error")
      callAndObserve(executor, responseObserver) {
        throw exception
      }
      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INTERNAL.code
      captor.value.status.cause shouldBe exception
      captor.value.status.description shouldBe "eid: INTERNAL_ERROR, text: Internal error"
    }

    "should callAndObserve and reconnect in case of HeosClientException" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      val exception = HeosClientException("Forced failure")
      var retries = 0
      callAndObserve(executor, responseObserver, { retries++ }) {
        throw exception
      }
      verify(responseObserver, timeout(1000)).onError(captor.capture())
      retries shouldBe 3
      captor.value.status.code shouldBe io.grpc.Status.INTERNAL.code
      captor.value.status.cause shouldBe exception
      captor.value.status.description shouldBe "Forced failure"
    }

    "should callAndObserve and build error response in case of other exception" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      val exception = Exception("Other error")
      callAndObserve(executor, responseObserver) {
        throw exception
      }
      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.UNKNOWN.code
      captor.value.status.cause shouldBe exception
      captor.value.status.description shouldBe "Other error"
    }
  }
}

internal class HeosSystemServiceTest : StringSpec() {
  private val executor = Executors.newSingleThreadExecutor()

  private val heosClient = mock<HeosClient>()

  private val heosSystemService = HeosSystemService(heosClient, executor)

  private val captor = ArgumentCaptor.forClass(io.grpc.StatusRuntimeException::class.java)

  override fun interceptSpec(context: Spec, spec: () -> Unit) {
    super.interceptSpec(context, spec)
    executor.shutdownNow()
  }

  init {
    "should check account" {
      val responseObserver = mock<StreamObserver<CheckAccountResponse>>()
      `when`(heosClient.checkAccount()).thenReturn(CheckAccountResponse.getDefaultInstance())

      heosSystemService.checkAccount(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(CheckAccountResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should sign in" {
      val responseObserver = mock<StreamObserver<SignInResponse>>()
      `when`(heosClient.signIn("foo", "bar")).thenReturn(SignInResponse.getDefaultInstance())

      heosSystemService.signIn(SignInRequest.newBuilder()
          .setUserName("foo")
          .setPassword("bar")
          .build(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SignInResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no user_name" {
      val responseObserver = mock<StreamObserver<SignInResponse>>()

      heosSystemService.signIn(SignInRequest.newBuilder().setPassword("bar").build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no password" {
      val responseObserver = mock<StreamObserver<SignInResponse>>()

      heosSystemService.signIn(SignInRequest.newBuilder().setUserName("foo").build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should sign out" {
      val responseObserver = mock<StreamObserver<SignOutResponse>>()
      `when`(heosClient.signOut()).thenReturn(SignOutResponse.getDefaultInstance())

      heosSystemService.signOut(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SignOutResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should reboot" {
      val responseObserver = mock<StreamObserver<RebootResponse>>()
      `when`(heosClient.reboot()).thenReturn(RebootResponse.getDefaultInstance())

      heosSystemService.reboot(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(RebootResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }
  }
}

internal class HeosPlayerServiceTest : StringSpec() {
  private val executor = Executors.newSingleThreadExecutor()

  private val heosClient = mock<HeosClient>()

  private val heosPlayerService = HeosPlayerService(heosClient, executor)

  private val captor = ArgumentCaptor.forClass(io.grpc.StatusRuntimeException::class.java)

  init {
    "should get players" {
      val responseObserver = mock<StreamObserver<GetPlayersResponse>>()
      `when`(heosClient.getPlayers()).thenReturn(GetPlayersResponse.getDefaultInstance())

      heosPlayerService.getPlayers(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetPlayersResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should get player info" {
      val responseObserver = mock<StreamObserver<GetPlayerInfoResponse>>()
      `when`(heosClient.getPlayerInfo("0")).thenReturn(GetPlayerInfoResponse.getDefaultInstance())

      heosPlayerService.getPlayerInfo(GetPlayerInfoRequest.newBuilder().setPid("0").build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetPlayerInfoResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetPlayerInfoResponse>>()

      heosPlayerService.getPlayerInfo(GetPlayerInfoRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get play state" {
      val responseObserver = mock<StreamObserver<GetPlayStateResponse>>()
      `when`(heosClient.getPlayState("0")).thenReturn(GetPlayStateResponse.getDefaultInstance())

      heosPlayerService.getPlayState(GetPlayStateRequest.newBuilder().setPid("0").build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetPlayStateResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetPlayStateResponse>>()

      heosPlayerService.getPlayState(GetPlayStateRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set play state" {
      val responseObserver = mock<StreamObserver<SetPlayStateResponse>>()
      `when`(heosClient.setPlayState("0", PlayState.State.PLAY))
          .thenReturn(SetPlayStateResponse.getDefaultInstance())

      heosPlayerService.setPlayState(SetPlayStateRequest.newBuilder()
          .setPid("0")
          .setState(PlayState.State.PLAY)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetPlayStateResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<SetPlayStateResponse>>()

      heosPlayerService.setPlayState(SetPlayStateRequest.newBuilder()
          .setState(PlayState.State.PLAY)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get now playing media" {
      val responseObserver = mock<StreamObserver<GetNowPlayingMediaResponse>>()
      `when`(heosClient.getNowPlayingMedia("0"))
          .thenReturn(GetNowPlayingMediaResponse.getDefaultInstance())

      heosPlayerService.getNowPlayingMedia(GetNowPlayingMediaRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetNowPlayingMediaResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetNowPlayingMediaResponse>>()

      heosPlayerService.getNowPlayingMedia(GetNowPlayingMediaRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get volume" {
      val responseObserver = mock<StreamObserver<GetVolumeResponse>>()
      `when`(heosClient.getVolume(PLAYER, "0"))
          .thenReturn(GetVolumeResponse.getDefaultInstance())

      heosPlayerService.getVolume(io.honnix.kheos.proto.player.v1.GetVolumeRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetVolumeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetVolumeResponse>>()

      heosPlayerService.getVolume(
          io.honnix.kheos.proto.player.v1.GetVolumeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set volume" {
      val responseObserver = mock<StreamObserver<SetVolumeResponse>>()
      `when`(heosClient.setVolume(PLAYER, "0", 10))
          .thenReturn(SetVolumeResponse.getDefaultInstance())

      heosPlayerService.setVolume(io.honnix.kheos.proto.player.v1.SetVolumeRequest.newBuilder()
          .setPid("0")
          .setLevel(10)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetVolumeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<SetVolumeResponse>>()

      heosPlayerService.setVolume(
          io.honnix.kheos.proto.player.v1.SetVolumeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should volume up" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()
      `when`(heosClient.volumeUp(PLAYER, "0", 3))
          .thenReturn(VolumeUpResponse.getDefaultInstance())

      heosPlayerService.volumeUp(io.honnix.kheos.proto.player.v1.VolumeUpRequest.newBuilder()
          .setPid("0")
          .setStep(3)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(VolumeUpResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()

      heosPlayerService.volumeUp(
          io.honnix.kheos.proto.player.v1.VolumeUpRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if invalid step" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()

      heosPlayerService.volumeUp(io.honnix.kheos.proto.player.v1.VolumeUpRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should volume down" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()
      `when`(heosClient.volumeDown(PLAYER, "0", 3))
          .thenReturn(VolumeDownResponse.getDefaultInstance())

      heosPlayerService.volumeDown(io.honnix.kheos.proto.player.v1.VolumeDownRequest.newBuilder()
          .setPid("0")
          .setStep(3)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(VolumeDownResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()

      heosPlayerService.volumeDown(io.honnix.kheos.proto.player.v1.VolumeDownRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if invalid step" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()

      heosPlayerService.volumeDown(
          io.honnix.kheos.proto.player.v1.VolumeDownRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get mute" {
      val responseObserver = mock<StreamObserver<GetMuteResponse>>()
      `when`(heosClient.getMute(PLAYER, "0"))
          .thenReturn(GetMuteResponse.getDefaultInstance())

      heosPlayerService.getMute(io.honnix.kheos.proto.player.v1.GetMuteRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetMuteResponse>>()

      heosPlayerService.getMute(io.honnix.kheos.proto.player.v1.GetMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set mute" {
      val responseObserver = mock<StreamObserver<SetMuteResponse>>()
      `when`(heosClient.setMute(PLAYER, "0", MuteState.State.ON))
          .thenReturn(SetMuteResponse.getDefaultInstance())

      heosPlayerService.setMute(io.honnix.kheos.proto.player.v1.SetMuteRequest.newBuilder()
          .setPid("0")
          .setState(MuteState.State.ON)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<SetMuteResponse>>()

      heosPlayerService.setMute(
          io.honnix.kheos.proto.player.v1.SetMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should toggle mute if no state" {
      val responseObserver = mock<StreamObserver<ToggleMuteResponse>>()
      `when`(heosClient.toggleMute(PLAYER, "0"))
          .thenReturn(ToggleMuteResponse.getDefaultInstance())

      heosPlayerService.toggleMute(io.honnix.kheos.proto.player.v1.ToggleMuteRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(ToggleMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<ToggleMuteResponse>>()

      heosPlayerService.toggleMute(
          io.honnix.kheos.proto.player.v1.ToggleMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get play mode" {
      val responseObserver = mock<StreamObserver<GetPlayModeResponse>>()
      `when`(heosClient.getPlayMode("0"))
          .thenReturn(GetPlayModeResponse.getDefaultInstance())

      heosPlayerService.getPlayMode(GetPlayModeRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetPlayModeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetPlayModeResponse>>()

      heosPlayerService.getPlayMode(GetPlayModeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set play mode" {
      val responseObserver = mock<StreamObserver<SetPlayModeResponse>>()
      `when`(heosClient.setPlayMode("0", PlayRepeatState.State.ON_ALL, PlayShuffleState.State.OFF))
          .thenReturn(SetPlayModeResponse.getDefaultInstance())

      heosPlayerService.setPlayMode(SetPlayModeRequest.newBuilder()
          .setPid("0")
          .setRepeat(PlayRepeatState.State.ON_ALL)
          .setShuffle(PlayShuffleState.State.OFF)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetPlayModeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should set play mode repeat" {
      val responseObserver = mock<StreamObserver<SetPlayModeResponse>>()
      `when`(heosClient.setPlayMode("0", PlayRepeatState.State.ON_ALL))
          .thenReturn(SetPlayModeResponse.getDefaultInstance())

      heosPlayerService.setPlayMode(SetPlayModeRequest.newBuilder()
          .setPid("0")
          .setRepeat(PlayRepeatState.State.ON_ALL)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetPlayModeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should set play mode shuffle" {
      val responseObserver = mock<StreamObserver<SetPlayModeResponse>>()
      `when`(heosClient.setPlayMode("0", PlayShuffleState.State.OFF))
          .thenReturn(SetPlayModeResponse.getDefaultInstance())

      heosPlayerService.setPlayMode(SetPlayModeRequest.newBuilder()
          .setPid("0")
          .setShuffle(PlayShuffleState.State.OFF)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetPlayModeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<SetPlayModeResponse>>()

      heosPlayerService.setPlayMode(SetPlayModeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing both states" {
      val responseObserver = mock<StreamObserver<SetPlayModeResponse>>()

      heosPlayerService.setPlayMode(SetPlayModeRequest.newBuilder()
          .setPid("0").build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get queue" {
      val responseObserver = mock<StreamObserver<GetQueueResponse>>()
      `when`(heosClient.getQueue("0", IntRange(0, 10)))
          .thenReturn(GetQueueResponse.getDefaultInstance())

      heosPlayerService.getQueue(GetQueueRequest.newBuilder()
          .setPid("0")
          .setStart(0)
          .setEnd(10)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<GetQueueResponse>>()

      heosPlayerService.getQueue(GetQueueRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should play queue" {
      val responseObserver = mock<StreamObserver<PlayQueueResponse>>()
      `when`(heosClient.playQueue("0", "0"))
          .thenReturn(PlayQueueResponse.getDefaultInstance())

      heosPlayerService.playQueue(PlayQueueRequest.newBuilder()
          .setPid("0")
          .setQid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(PlayQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<PlayQueueResponse>>()

      heosPlayerService.playQueue(PlayQueueRequest.newBuilder()
          .setQid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no qid" {
      val responseObserver = mock<StreamObserver<PlayQueueResponse>>()

      heosPlayerService.playQueue(PlayQueueRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should remove elements from queue" {
      val responseObserver = mock<StreamObserver<RemoveFromQueueResponse>>()
      `when`(heosClient.removeFromQueue("0", listOf("0", "1", "2")))
          .thenReturn(RemoveFromQueueResponse.getDefaultInstance())

      heosPlayerService.removeFromQueue(RemoveFromQueueRequest.newBuilder()
          .setPid("0")
          .addAllQids(listOf("0", "1", "2"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(RemoveFromQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<RemoveFromQueueResponse>>()

      heosPlayerService.removeFromQueue(RemoveFromQueueRequest.newBuilder()
          .addAllQids(listOf("0", "1"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no qids" {
      val responseObserver = mock<StreamObserver<RemoveFromQueueResponse>>()

      heosPlayerService.removeFromQueue(RemoveFromQueueRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should save queue" {
      val responseObserver = mock<StreamObserver<SaveQueueResponse>>()
      `when`(heosClient.saveQueue("0", "foo"))
          .thenReturn(SaveQueueResponse.getDefaultInstance())

      heosPlayerService.saveQueue(SaveQueueRequest.newBuilder()
          .setPid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SaveQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<SaveQueueResponse>>()

      heosPlayerService.saveQueue(SaveQueueRequest.newBuilder()
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no name" {
      val responseObserver = mock<StreamObserver<SaveQueueResponse>>()

      heosPlayerService.saveQueue(SaveQueueRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should clear queue" {
      val responseObserver = mock<StreamObserver<ClearQueueResponse>>()
      `when`(heosClient.clearQueue("0"))
          .thenReturn(ClearQueueResponse.getDefaultInstance())

      heosPlayerService.clearQueue(ClearQueueRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(ClearQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<ClearQueueResponse>>()

      heosPlayerService.clearQueue(ClearQueueRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should play previous" {
      val responseObserver = mock<StreamObserver<PlayPreviousResponse>>()
      `when`(heosClient.playPrevious("0"))
          .thenReturn(PlayPreviousResponse.getDefaultInstance())

      heosPlayerService.playPrevious(PlayPreviousRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(PlayPreviousResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<PlayPreviousResponse>>()

      heosPlayerService.playPrevious(PlayPreviousRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should play next" {
      val responseObserver = mock<StreamObserver<PlayNextResponse>>()
      `when`(heosClient.playNext("0"))
          .thenReturn(PlayNextResponse.getDefaultInstance())

      heosPlayerService.playNext(PlayNextRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(PlayNextResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no pid" {
      val responseObserver = mock<StreamObserver<PlayNextResponse>>()

      heosPlayerService.playNext(PlayNextRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }
  }
}

internal class HeosGroupServiceTest : StringSpec() {
  private val executor = Executors.newSingleThreadExecutor()

  private val heosClient = mock<HeosClient>()

  private val heosGroupService = HeosGroupService(heosClient, executor)

  private val captor = ArgumentCaptor.forClass(io.grpc.StatusRuntimeException::class.java)

  override fun interceptSpec(context: Spec, spec: () -> Unit) {
    super.interceptSpec(context, spec)
    executor.shutdownNow()
  }

  init {
    "should get groups" {
      val responseObserver = mock<StreamObserver<GetGroupsResponse>>()
      `when`(heosClient.getGroups()).thenReturn(GetGroupsResponse.getDefaultInstance())

      heosGroupService.getGroups(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetGroupsResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should get group info" {
      val responseObserver = mock<StreamObserver<GetGroupInfoResponse>>()
      `when`(heosClient.getGroupInfo("0")).thenReturn(GetGroupInfoResponse.getDefaultInstance())

      heosGroupService.getGroupInfo(GetGroupInfoRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetGroupInfoResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<GetGroupInfoResponse>>()

      heosGroupService.getGroupInfo(GetGroupInfoRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set group" {
      val responseObserver = mock<StreamObserver<SetGroupResponse>>()
      `when`(heosClient.setGroup("0", listOf("1", "2")))
          .thenReturn(SetGroupResponse.getDefaultInstance())

      heosGroupService.setGroup(SetGroupRequest.newBuilder()
          .setLeader("0")
          .addAllMembers(listOf("1", "2"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetGroupResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no leader id" {
      val responseObserver = mock<StreamObserver<SetGroupResponse>>()

      heosGroupService.setGroup(SetGroupRequest.newBuilder()
          .addAllMembers(listOf("1", "2"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no members" {
      val responseObserver = mock<StreamObserver<SetGroupResponse>>()

      heosGroupService.setGroup(SetGroupRequest.newBuilder()
          .setLeader("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should delete group" {
      val responseObserver = mock<StreamObserver<DeleteGroupResponse>>()
      `when`(heosClient.deleteGroup("0"))
          .thenReturn(DeleteGroupResponse.getDefaultInstance())

      heosGroupService.deleteGroup(DeleteGroupRequest.newBuilder()
          .setLeader("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(DeleteGroupResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no leader id" {
      val responseObserver = mock<StreamObserver<DeleteGroupResponse>>()

      heosGroupService.deleteGroup(DeleteGroupRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get volume" {
      val responseObserver = mock<StreamObserver<GetVolumeResponse>>()
      `when`(heosClient.getVolume(GROUP, "0"))
          .thenReturn(GetVolumeResponse.getDefaultInstance())

      heosGroupService.getVolume(io.honnix.kheos.proto.group.v1.GetVolumeRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetVolumeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<GetVolumeResponse>>()

      heosGroupService.getVolume(
          io.honnix.kheos.proto.group.v1.GetVolumeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set volume" {
      val responseObserver = mock<StreamObserver<SetVolumeResponse>>()
      `when`(heosClient.setVolume(GROUP, "0", 10))
          .thenReturn(SetVolumeResponse.getDefaultInstance())

      heosGroupService.setVolume(io.honnix.kheos.proto.group.v1.SetVolumeRequest.newBuilder()
          .setGid("0")
          .setLevel(10)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetVolumeResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<SetVolumeResponse>>()

      heosGroupService.setVolume(
          io.honnix.kheos.proto.group.v1.SetVolumeRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should volume up" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()
      `when`(heosClient.volumeUp(GROUP, "0", 3))
          .thenReturn(VolumeUpResponse.getDefaultInstance())

      heosGroupService.volumeUp(io.honnix.kheos.proto.group.v1.VolumeUpRequest.newBuilder()
          .setGid("0")
          .setStep(3)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(VolumeUpResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()

      heosGroupService.volumeUp(
          io.honnix.kheos.proto.group.v1.VolumeUpRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if invalid step" {
      val responseObserver = mock<StreamObserver<VolumeUpResponse>>()

      heosGroupService.volumeUp(io.honnix.kheos.proto.group.v1.VolumeUpRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should volume down" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()
      `when`(heosClient.volumeDown(GROUP, "0", 3))
          .thenReturn(VolumeDownResponse.getDefaultInstance())

      heosGroupService.volumeDown(io.honnix.kheos.proto.group.v1.VolumeDownRequest.newBuilder()
          .setGid("0")
          .setStep(3)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(VolumeDownResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()

      heosGroupService.volumeDown(io.honnix.kheos.proto.group.v1.VolumeDownRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if invalid step" {
      val responseObserver = mock<StreamObserver<VolumeDownResponse>>()

      heosGroupService.volumeDown(
          io.honnix.kheos.proto.group.v1.VolumeDownRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get mute" {
      val responseObserver = mock<StreamObserver<GetMuteResponse>>()
      `when`(heosClient.getMute(GROUP, "0"))
          .thenReturn(GetMuteResponse.getDefaultInstance())

      heosGroupService.getMute(io.honnix.kheos.proto.group.v1.GetMuteRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<GetMuteResponse>>()

      heosGroupService.getMute(io.honnix.kheos.proto.group.v1.GetMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set mute" {
      val responseObserver = mock<StreamObserver<SetMuteResponse>>()
      `when`(heosClient.setMute(GROUP, "0", MuteState.State.ON))
          .thenReturn(SetMuteResponse.getDefaultInstance())

      heosGroupService.setMute(io.honnix.kheos.proto.group.v1.SetMuteRequest.newBuilder()
          .setGid("0")
          .setState(MuteState.State.ON)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<SetMuteResponse>>()

      heosGroupService.setMute(
          io.honnix.kheos.proto.group.v1.SetMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should toggle mute if no state" {
      val responseObserver = mock<StreamObserver<ToggleMuteResponse>>()
      `when`(heosClient.toggleMute(CommandGroup.GROUP, "0"))
          .thenReturn(ToggleMuteResponse.getDefaultInstance())

      heosGroupService.toggleMute(io.honnix.kheos.proto.group.v1.ToggleMuteRequest.newBuilder()
          .setGid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(ToggleMuteResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no gid" {
      val responseObserver = mock<StreamObserver<ToggleMuteResponse>>()

      heosGroupService.toggleMute(
          io.honnix.kheos.proto.group.v1.ToggleMuteRequest.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }
  }
}

internal class HeosBrowseServiceTest : StringSpec() {
  private val executor = Executors.newSingleThreadExecutor()

  private val heosClient = mock<HeosClient>()

  private val heosBrowseService = HeosBrowseService(heosClient, executor)

  private val captor = ArgumentCaptor.forClass(io.grpc.StatusRuntimeException::class.java)

  override fun interceptSpec(context: Spec, spec: () -> Unit) {
    super.interceptSpec(context, spec)
    executor.shutdownNow()
  }

  init {
    "should get music sources" {
      val responseObserver = mock<StreamObserver<GetMusicSourcesResponse>>()
      `when`(heosClient.getMusicSources()).thenReturn(GetMusicSourcesResponse.getDefaultInstance())

      heosBrowseService.getMusicSources(Empty.getDefaultInstance(), responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetMusicSourcesResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should get music source info" {
      val responseObserver = mock<StreamObserver<GetMusicSourceInfoResponse>>()
      `when`(heosClient.getMusicSourceInfo("0"))
          .thenReturn(GetMusicSourceInfoResponse.getDefaultInstance())

      heosBrowseService.getMusicSourceInfo(GetMusicSourceInfoRequest.newBuilder()
          .setSid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetMusicSourceInfoResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no sid" {
      val responseObserver = mock<StreamObserver<GetMusicSourceInfoResponse>>()

      heosBrowseService.getMusicSourceInfo(GetMusicSourceInfoRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should browse media sources" {
      val responseObserver = mock<StreamObserver<BrowseMediaSourcesResponse>>()
      `when`(heosClient.browseMediaSources("0", IntRange(1, 5)))
          .thenReturn(BrowseMediaSourcesResponse.getDefaultInstance())

      heosBrowseService.browseMediaSources(BrowseMediaSourcesRequest.newBuilder()
          .setSid("0")
          .setStart(1)
          .setEnd(5)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(BrowseMediaSourcesResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no sid" {
      val responseObserver = mock<StreamObserver<BrowseMediaSourcesResponse>>()

      heosBrowseService.browseMediaSources(BrowseMediaSourcesRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should browse top music" {
      val responseObserver = mock<StreamObserver<BrowseTopMusicResponse>>()
      `when`(heosClient.browseTopMusic("0", IntRange(1, 5)))
          .thenReturn(BrowseTopMusicResponse.getDefaultInstance())

      heosBrowseService.browseTopMusic(BrowseTopMusicRequest.newBuilder()
          .setSid("0")
          .setStart(1)
          .setEnd(5)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(BrowseTopMusicResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no sid" {
      val responseObserver = mock<StreamObserver<BrowseTopMusicResponse>>()

      heosBrowseService.browseTopMusic(BrowseTopMusicRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should browse source containers" {
      val responseObserver = mock<StreamObserver<BrowseSourceContainersResponse>>()
      `when`(heosClient.browseSourceContainers("0", "0", IntRange(1, 5)))
          .thenReturn(BrowseSourceContainersResponse.getDefaultInstance())

      heosBrowseService.browseSourceContainers(BrowseSourceContainersRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .setStart(1)
          .setEnd(5)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(BrowseSourceContainersResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no sid" {
      val responseObserver = mock<StreamObserver<BrowseSourceContainersResponse>>()

      heosBrowseService.browseSourceContainers(BrowseSourceContainersRequest.newBuilder()
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if no cid" {
      val responseObserver = mock<StreamObserver<BrowseSourceContainersResponse>>()

      heosBrowseService.browseSourceContainers(BrowseSourceContainersRequest.newBuilder()
          .setSid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should get search criteria" {
      val responseObserver = mock<StreamObserver<GetSearchCriteriaResponse>>()
      `when`(heosClient.getSearchCriteria("0"))
          .thenReturn(GetSearchCriteriaResponse.getDefaultInstance())

      heosBrowseService.getSearchCriteria(GetSearchCriteriaRequest.newBuilder()
          .setSid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(GetSearchCriteriaResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if no sid" {
      val responseObserver = mock<StreamObserver<GetSearchCriteriaResponse>>()

      heosBrowseService.getSearchCriteria(GetSearchCriteriaRequest.getDefaultInstance(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should search" {
      val responseObserver = mock<StreamObserver<SearchResponse>>()
      `when`(heosClient.search("0", Scid.ARTIST, "*", IntRange(0, 1)))
          .thenReturn(SearchResponse.getDefaultInstance())

      heosBrowseService.search(SearchRequest.newBuilder()
          .setSid("0")
          .setScid(Scid.ARTIST)
          .setSearch("*")
          .setStart(0)
          .setEnd(1)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SearchResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<SearchResponse>>()

      heosBrowseService.search(SearchRequest.newBuilder()
          .setScid(Scid.ARTIST)
          .setSearch("*")
          .setStart(0)
          .setEnd(1)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing search_string" {
      val responseObserver = mock<StreamObserver<SearchResponse>>()

      heosBrowseService.search(SearchRequest.newBuilder()
          .setSid("0")
          .setScid(Scid.ARTIST)
          .setStart(0)
          .setEnd(1)
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should play stream" {
      val responseObserver = mock<StreamObserver<PlayStreamResponse>>()
      `when`(heosClient.playStream("0", "0", "0", "foo", "0"))
          .thenReturn(PlayStreamResponse.getDefaultInstance())

      heosBrowseService.playStream(PlayStreamRequest.newBuilder()
          .setPid("0")
          .setSid("0")
          .setCid("0")
          .setMid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(PlayStreamResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing pid" {
      val responseObserver = mock<StreamObserver<PlayStreamResponse>>()

      heosBrowseService.playStream(PlayStreamRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .setMid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<PlayStreamResponse>>()

      heosBrowseService.playStream(PlayStreamRequest.newBuilder()
          .setPid("0")
          .setCid("0")
          .setMid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing mid" {
      val responseObserver = mock<StreamObserver<PlayStreamResponse>>()

      heosBrowseService.playStream(PlayStreamRequest.newBuilder()
          .setPid("0")
          .setSid("0")
          .setCid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing name" {
      val responseObserver = mock<StreamObserver<PlayStreamResponse>>()

      heosBrowseService.playStream(PlayStreamRequest.newBuilder()
          .setPid("0")
          .setSid("0")
          .setCid("0")
          .setMid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should play input" {
      val responseObserver = mock<StreamObserver<PlayInputResponse>>()
      `when`(heosClient.playInput("0", "0", "0", "input/aux_in_1"))
          .thenReturn(PlayInputResponse.getDefaultInstance())

      heosBrowseService.playInput(PlayInputRequest.newBuilder()
          .setPid("0")
          .setMid("0")
          .setSpid("0")
          .setInput("input/aux_in_1")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(PlayInputResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing pid" {
      val responseObserver = mock<StreamObserver<PlayInputResponse>>()

      heosBrowseService.playInput(PlayInputRequest.newBuilder()
          .setMid("0")
          .setSpid("0")
          .setInput("input/aux_in_1")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing mid, spid, and input" {
      val responseObserver = mock<StreamObserver<PlayInputResponse>>()

      heosBrowseService.playInput(PlayInputRequest.newBuilder()
          .setPid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should add to queue" {
      val responseObserver = mock<StreamObserver<AddToQueueResponse>>()
      `when`(heosClient.addToQueue("0", "0", "0", AddToQueueRequest.AddCriteriaId.ADD_TO_END, "0"))
          .thenReturn(AddToQueueResponse.getDefaultInstance())

      heosBrowseService.addToQueue(AddToQueueRequest.newBuilder()
          .setPid("0")
          .setSid("0")
          .setCid("0")
          .setAid(AddToQueueRequest.AddCriteriaId.ADD_TO_END)
          .setMid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(AddToQueueResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing pid" {
      val responseObserver = mock<StreamObserver<AddToQueueResponse>>()

      heosBrowseService.addToQueue(AddToQueueRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .setAid(AddToQueueRequest.AddCriteriaId.ADD_TO_END)
          .setMid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<AddToQueueResponse>>()

      heosBrowseService.addToQueue(AddToQueueRequest.newBuilder()
          .setPid("0")
          .setCid("0")
          .setAid(AddToQueueRequest.AddCriteriaId.ADD_TO_END)
          .setMid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing cid" {
      val responseObserver = mock<StreamObserver<AddToQueueResponse>>()

      heosBrowseService.addToQueue(AddToQueueRequest.newBuilder()
          .setPid("0")
          .setSid("0")
          .setAid(AddToQueueRequest.AddCriteriaId.ADD_TO_END)
          .setMid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should rename playlist" {
      val responseObserver = mock<StreamObserver<RenamePlaylistResponse>>()
      `when`(heosClient.renamePlaylist("0", "0", "foo"))
          .thenReturn(RenamePlaylistResponse.getDefaultInstance())

      heosBrowseService.renamePlaylist(RenamePlaylistRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(RenamePlaylistResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<RenamePlaylistResponse>>()

      heosBrowseService.renamePlaylist(RenamePlaylistRequest.newBuilder()
          .setCid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing cid" {
      val responseObserver = mock<StreamObserver<RenamePlaylistResponse>>()

      heosBrowseService.renamePlaylist(RenamePlaylistRequest.newBuilder()
          .setSid("0")
          .setName("foo")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing name" {
      val responseObserver = mock<StreamObserver<RenamePlaylistResponse>>()

      heosBrowseService.renamePlaylist(RenamePlaylistRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should delete playlist" {
      val responseObserver = mock<StreamObserver<DeletePlaylistResponse>>()
      `when`(heosClient.deletePlaylist("0", "0"))
          .thenReturn(DeletePlaylistResponse.getDefaultInstance())

      heosBrowseService.deletePlaylist(DeletePlaylistRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(DeletePlaylistResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<DeletePlaylistResponse>>()

      heosBrowseService.deletePlaylist(DeletePlaylistRequest.newBuilder()
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing cid" {
      val responseObserver = mock<StreamObserver<DeletePlaylistResponse>>()

      heosBrowseService.deletePlaylist(DeletePlaylistRequest.newBuilder()
          .setSid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should retrieve metadata" {
      val responseObserver = mock<StreamObserver<RetrieveMetadataResponse>>()
      `when`(heosClient.retrieveMetadata("0", "0"))
          .thenReturn(RetrieveMetadataResponse.getDefaultInstance())

      heosBrowseService.retrieveMetadata(RetrieveMetadataRequest.newBuilder()
          .setSid("0")
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(RetrieveMetadataResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if missing sid" {
      val responseObserver = mock<StreamObserver<RetrieveMetadataResponse>>()

      heosBrowseService.retrieveMetadata(RetrieveMetadataRequest.newBuilder()
          .setCid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should return client error if missing cid" {
      val responseObserver = mock<StreamObserver<RetrieveMetadataResponse>>()

      heosBrowseService.retrieveMetadata(RetrieveMetadataRequest.newBuilder()
          .setSid("0")
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    "should set service option" {
      val responseObserver = mock<StreamObserver<SetServiceOptionResponse>>()
      `when`(heosClient.setServiceOption(OptionId.CREATE_NEW_STATION, AttributesBuilder()
          .add("sid", "0")
          .add("name", "foo")
          .build(),
          IntRange(1, 10)))
          .thenReturn(SetServiceOptionResponse.getDefaultInstance())

      heosBrowseService.setServiceOption(SetServiceOptionRequest.newBuilder()
          .setOption(OptionId.CREATE_NEW_STATION)
          .putAllValues(mapOf("sid" to "0", "name" to "foo", "range" to "1,10"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetServiceOptionResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should set service option with default range" {
      val responseObserver = mock<StreamObserver<SetServiceOptionResponse>>()
      `when`(heosClient.setServiceOption(OptionId.CREATE_NEW_STATION, AttributesBuilder()
          .add("sid", "0")
          .add("name", "foo")
          .build()))
          .thenReturn(SetServiceOptionResponse.getDefaultInstance())

      heosBrowseService.setServiceOption(SetServiceOptionRequest.newBuilder()
          .setOption(OptionId.CREATE_NEW_STATION)
          .putAllValues(mapOf("sid" to "0", "name" to "foo"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onNext(SetServiceOptionResponse.getDefaultInstance())
      verify(responseObserver, timeout(1000)).onCompleted()
    }

    "should return client error if invalid range" {
      val responseObserver = mock<StreamObserver<SetServiceOptionResponse>>()

      heosBrowseService.setServiceOption(SetServiceOptionRequest.newBuilder()
          .setOption(OptionId.CREATE_NEW_STATION)
          .putAllValues(mapOf("sid" to "0", "name" to "foo", "range" to "foo"))
          .build(),
          responseObserver)

      verify(responseObserver, timeout(1000)).onError(captor.capture())
      captor.value.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }
  }
}
