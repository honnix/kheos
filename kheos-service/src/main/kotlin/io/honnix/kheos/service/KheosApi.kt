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
import io.honnix.kheos.lib.HeosClient
import io.honnix.kheos.lib.HeosClientException
import io.honnix.kheos.lib.HeosCommandException
import io.honnix.kheos.proto.base.v1.*
import io.honnix.kheos.proto.browse.v1.*
import io.honnix.kheos.proto.group.v1.*
import io.honnix.kheos.proto.group.v1.GroupServiceGrpc.GroupServiceImplBase
import io.honnix.kheos.proto.player.v1.*
import io.honnix.kheos.proto.player.v1.PlayerServiceGrpc.PlayerServiceImplBase
import io.honnix.kheos.proto.system.v1.*
import io.honnix.kheos.proto.system.v1.SystemServiceGrpc.SystemServiceImplBase
import javaslang.control.Try
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Supplier

private val logger = LoggerFactory.getLogger(object {}::class.java.`package`.name)

internal fun <T> callAndObserve(executor: Executor,
                                responseObserver: StreamObserver<T>,
                                h: () -> Unit = {},
                                retries: Int = 3,
                                f: () -> T) {
  CompletableFuture.supplyAsync(Supplier { f() }, executor)
      .thenAccept {
        responseObserver.onNext(it)
        responseObserver.onCompleted()
      }
      .exceptionally {
        handleException(it.cause!!, executor, responseObserver, h, retries, f)
        null
      }
}

private fun <T> handleException(t: Throwable, executor: Executor,
                                responseObserver: StreamObserver<T>,
                                h: () -> Unit = {},
                                retries: Int,
                                f: () -> T) {
  when (t) {
    is IllegalArgumentException -> {
      logger.debug("bad request", t)
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription(t.message)
              .withCause(t)
              .asRuntimeException())
    }
    is HeosCommandException -> {
      logger.info("failed to execute command", t)
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription(t.message)
              .withCause(t)
              .asRuntimeException())
    }
    is HeosClientException -> {
      if (retries == 0) {
        logger.error("failed to send command after retries and this is unlikely to recover")
        responseObserver.onError(
            io.grpc.Status.INTERNAL
                .withDescription(t.message)
                .withCause(t)
                .asRuntimeException())
      } else {
        logger.warn("failed to send command, will retry $retries time${if (retries > 1) "s" else ""}", t)
        h()
        callAndObserve(executor, responseObserver, h, retries - 1, f)
      }
    }
    is Exception -> {
      logger.error("unexpected exception", t)
      responseObserver.onError(
          io.grpc.Status.UNKNOWN
              .withDescription(t.message)
              .withCause(t)
              .asRuntimeException())
    }
  }
}

class HeosSystemService(private val heosClient: HeosClient, private val executor: Executor) :
    SystemServiceImplBase() {
  override fun checkAccount(request: Empty?, responseObserver: StreamObserver<CheckAccountResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.checkAccount()
      }

  override fun signIn(request: SignInRequest?, responseObserver: StreamObserver<SignInResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.userName.isNullOrBlank()) {
          throw IllegalArgumentException("missing user_name")
        }
        if (request.password.isNullOrBlank()) {
          throw IllegalArgumentException("missing password")
        }
        heosClient.signIn(request.userName, request.password)
      }

  override fun signOut(request: Empty?, responseObserver: StreamObserver<SignOutResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.signOut()
      }

  override fun reboot(request: Empty?, responseObserver: StreamObserver<RebootResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.reboot()
      }
}

class HeosPlayerService(private val heosClient: HeosClient, private val executor: Executor) :
    PlayerServiceImplBase() {
  override fun getPlayers(request: Empty?, responseObserver: StreamObserver<GetPlayersResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.getPlayers()
      }

  override fun getPlayerInfo(request: GetPlayerInfoRequest?,
                             responseObserver: StreamObserver<GetPlayerInfoResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getPlayerInfo(request.pid)
      }

  override fun getPlayState(request: GetPlayStateRequest?,
                            responseObserver: StreamObserver<GetPlayStateResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getPlayState(request.pid)
      }

  override fun setPlayState(request: SetPlayStateRequest?,
                            responseObserver: StreamObserver<SetPlayStateResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.setPlayState(request.pid, request.state)
      }

  override fun getNowPlayingMedia(request: GetNowPlayingMediaRequest?,
                                  responseObserver: StreamObserver<GetNowPlayingMediaResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getNowPlayingMedia(request.pid)
      }

  override fun getVolume(request: io.honnix.kheos.proto.player.v1.GetVolumeRequest?,
                         responseObserver: StreamObserver<GetVolumeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getVolume(CommandGroup.PLAYER, request.pid)
      }

  override fun setVolume(request: io.honnix.kheos.proto.player.v1.SetVolumeRequest?,
                         responseObserver: StreamObserver<SetVolumeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.setVolume(CommandGroup.PLAYER, request.pid, request.level)
      }

  override fun volumeUp(request: io.honnix.kheos.proto.player.v1.VolumeUpRequest?,
                        responseObserver: StreamObserver<VolumeUpResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.step == 0) {
          throw IllegalArgumentException("invalid step")
        }
        heosClient.volumeUp(CommandGroup.PLAYER, request.pid, request.step)
      }

  override fun volumeDown(request: io.honnix.kheos.proto.player.v1.VolumeDownRequest?,
                          responseObserver: StreamObserver<VolumeDownResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.step == 0) {
          throw IllegalArgumentException("invalid step")
        }
        heosClient.volumeDown(CommandGroup.PLAYER, request.pid, request.step)
      }

  override fun getMute(request: io.honnix.kheos.proto.player.v1.GetMuteRequest?,
                       responseObserver: StreamObserver<GetMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getMute(CommandGroup.PLAYER, request.pid)
      }

  override fun setMute(request: io.honnix.kheos.proto.player.v1.SetMuteRequest?,
                       responseObserver: StreamObserver<SetMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.setMute(CommandGroup.PLAYER, request.pid, request.state)
      }

  override fun toggleMute(request: io.honnix.kheos.proto.player.v1.ToggleMuteRequest?,
                          responseObserver: StreamObserver<ToggleMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.toggleMute(CommandGroup.PLAYER, request.pid)
      }

  override fun getPlayMode(request: GetPlayModeRequest?,
                           responseObserver: StreamObserver<GetPlayModeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getPlayMode(request.pid)
      }

  override fun setPlayMode(request: SetPlayModeRequest?,
                           responseObserver: StreamObserver<SetPlayModeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }

        if (request.repeat === PlayRepeatState.State.NOP &&
            request.shuffle === PlayShuffleState.State.NOP) {
          throw IllegalArgumentException("missing both repeat and shuffle states")
        }

        if (request.repeat !== PlayRepeatState.State.NOP &&
            request.shuffle === PlayShuffleState.State.NOP) {
          heosClient.setPlayMode(request.pid, request.repeat)
        } else if (request.repeat === PlayRepeatState.State.NOP &&
            request.shuffle !== PlayShuffleState.State.NOP) {
          heosClient.setPlayMode(request.pid, request.shuffle)
        } else {
          heosClient.setPlayMode(request.pid, request.repeat, request.shuffle)
        }
      }

  override fun getQueue(request: GetQueueRequest?,
                        responseObserver: StreamObserver<GetQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.getQueue(request.pid, IntRange(request.start, request.end))
      }

  override fun playQueue(request: PlayQueueRequest?,
                         responseObserver: StreamObserver<PlayQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.qid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.playQueue(request.pid, request.qid)
      }

  override fun removeFromQueue(request: RemoveFromQueueRequest?,
                               responseObserver: StreamObserver<RemoveFromQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.qidsList.isEmpty()) {
          throw IllegalArgumentException("missing qids")
        }
        heosClient.removeFromQueue(request.pid, request.qidsList)
      }

  override fun saveQueue(request: SaveQueueRequest?,
                         responseObserver: StreamObserver<SaveQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.name.isNullOrBlank()) {
          throw IllegalArgumentException("missing name")
        }
        heosClient.saveQueue(request.pid, request.name)
      }

  override fun clearQueue(request: ClearQueueRequest?,
                          responseObserver: StreamObserver<ClearQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.clearQueue(request.pid)
      }

  override fun playPrevious(request: PlayPreviousRequest?,
                            responseObserver: StreamObserver<PlayPreviousResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.playPrevious(request.pid)
      }

  override fun playNext(request: PlayNextRequest?,
                        responseObserver: StreamObserver<PlayNextResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        heosClient.playNext(request.pid)
      }
}

class HeosGroupService(private val heosClient: HeosClient, private val executor: Executor) :
    GroupServiceImplBase() {
  override fun getGroups(request: Empty?,
                         responseObserver: StreamObserver<GetGroupsResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.getGroups()
      }

  override fun getGroupInfo(request: GetGroupInfoRequest?,
                            responseObserver: StreamObserver<GetGroupInfoResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.getGroupInfo(request.gid)
      }

  override fun setGroup(request: SetGroupRequest?,
                        responseObserver: StreamObserver<SetGroupResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.leader.isNullOrBlank()) {
          throw IllegalArgumentException("missing leader")
        }
        if (request.membersList.isEmpty()) {
          throw IllegalArgumentException("missing member(s)")
        }
        heosClient.setGroup(request.leader, request.membersList)
      }

  override fun deleteGroup(request: DeleteGroupRequest?,
                           responseObserver: StreamObserver<DeleteGroupResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.leader.isNullOrBlank()) {
          throw IllegalArgumentException("missing leader")
        }
        heosClient.deleteGroup(request.leader)
      }

  override fun getVolume(request: io.honnix.kheos.proto.group.v1.GetVolumeRequest?,
                         responseObserver: StreamObserver<GetVolumeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.getVolume(CommandGroup.GROUP, request.gid)
      }

  override fun setVolume(request: io.honnix.kheos.proto.group.v1.SetVolumeRequest?,
                         responseObserver: StreamObserver<SetVolumeResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.setVolume(CommandGroup.GROUP, request.gid, request.level)
      }

  override fun volumeUp(request: io.honnix.kheos.proto.group.v1.VolumeUpRequest?,
                        responseObserver: StreamObserver<VolumeUpResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        if (request.step == 0) {
          throw IllegalArgumentException("invalid step")
        }
        heosClient.volumeUp(CommandGroup.GROUP, request.gid, request.step)
      }

  override fun volumeDown(request: io.honnix.kheos.proto.group.v1.VolumeDownRequest?,
                          responseObserver: StreamObserver<VolumeDownResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        if (request.step == 0) {
          throw IllegalArgumentException("invalid step")
        }
        heosClient.volumeDown(CommandGroup.GROUP, request.gid, request.step)
      }

  override fun getMute(request: io.honnix.kheos.proto.group.v1.GetMuteRequest?,
                       responseObserver: StreamObserver<GetMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.getMute(CommandGroup.GROUP, request.gid)
      }

  override fun setMute(request: io.honnix.kheos.proto.group.v1.SetMuteRequest?,
                       responseObserver: StreamObserver<SetMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.setMute(CommandGroup.GROUP, request.gid, request.state)
      }

  override fun toggleMute(request: io.honnix.kheos.proto.group.v1.ToggleMuteRequest?,
                          responseObserver: StreamObserver<ToggleMuteResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.gid.isNullOrBlank()) {
          throw IllegalArgumentException("missing gid")
        }
        heosClient.toggleMute(CommandGroup.GROUP, request.gid)
      }
}

class HeosBrowseService(private val heosClient: HeosClient, private val executor: Executor) :
    BrowseServiceGrpc.BrowseServiceImplBase() {
  override fun getMusicSources(request: Empty?,
                               responseObserver: StreamObserver<GetMusicSourcesResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        heosClient.getMusicSources()
      }

  override fun getMusicSourceInfo(request: GetMusicSourceInfoRequest?,
                                  responseObserver: StreamObserver<GetMusicSourceInfoResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        heosClient.getMusicSourceInfo(request.sid)
      }

  override fun browseMediaSources(request: BrowseMediaSourcesRequest?,
                                  responseObserver: StreamObserver<BrowseMediaSourcesResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        heosClient.browseMediaSources(request.sid, IntRange(request.start, request.end))
      }

  override fun browseTopMusic(request: BrowseTopMusicRequest?,
                              responseObserver: StreamObserver<BrowseTopMusicResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        heosClient.browseTopMusic(request.sid, IntRange(request.start, request.end))
      }

  override fun browseSourceContainers(request: BrowseSourceContainersRequest?,
                                      responseObserver: StreamObserver<BrowseSourceContainersResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.cid.isNullOrBlank()) {
          throw IllegalArgumentException("missing cid")
        }
        heosClient.browseSourceContainers(request.sid, request.cid,
            IntRange(request.start, request.end))
      }

  override fun getSearchCriteria(request: GetSearchCriteriaRequest?,
                                 responseObserver: StreamObserver<GetSearchCriteriaResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        heosClient.getSearchCriteria(request.sid)
      }

  override fun search(request: SearchRequest?,
                      responseObserver: StreamObserver<SearchResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.search.isNullOrBlank()) {
          throw IllegalArgumentException("missing search string")
        }
        heosClient.search(request.sid, request.scid, request.search,
            IntRange(request.start, request.end))
      }

  override fun playStream(request: PlayStreamRequest?,
                          responseObserver: StreamObserver<PlayStreamResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.mid.isNullOrBlank()) {
          throw IllegalArgumentException("missing mid")
        }
        if (request.name.isNullOrBlank()) {
          throw IllegalArgumentException("missing name")
        }
        heosClient.playStream(request.pid, request.sid, request.mid, request.name, request.cid)
      }

  override fun playInput(request: PlayInputRequest?,
                         responseObserver: StreamObserver<PlayInputResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.mid.isNullOrBlank() &&
            request.spid.isNullOrBlank() &&
            request.input.isNullOrBlank()) {
          throw IllegalArgumentException("neither mid, spid nor input is provided")
        }
        heosClient.playInput(request.pid, request.mid, request.spid, request.input)
      }

  override fun addToQueue(request: AddToQueueRequest?,
                          responseObserver: StreamObserver<AddToQueueResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.pid.isNullOrBlank()) {
          throw IllegalArgumentException("missing pid")
        }
        if (request.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.cid.isNullOrBlank()) {
          throw IllegalArgumentException("missing cid")
        }
        heosClient.addToQueue(request.pid, request.sid, request.cid, request.aid, request.mid)
      }

  override fun renamePlaylist(request: RenamePlaylistRequest?,
                              responseObserver: StreamObserver<RenamePlaylistResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.cid.isNullOrBlank()) {
          throw IllegalArgumentException("missing cid")
        }
        if (request.name.isNullOrBlank()) {
          throw IllegalArgumentException("missing name")
        }
        heosClient.renamePlaylist(request.sid, request.cid, request.name)
      }

  override fun deletePlaylist(request: DeletePlaylistRequest?,
                              responseObserver: StreamObserver<DeletePlaylistResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.cid.isNullOrBlank()) {
          throw IllegalArgumentException("missing cid")
        }
        heosClient.deletePlaylist(request.sid, request.cid)
      }

  override fun retrieveMetadata(request: RetrieveMetadataRequest?,
                                responseObserver: StreamObserver<RetrieveMetadataResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        if (request!!.sid.isNullOrBlank()) {
          throw IllegalArgumentException("missing sid")
        }
        if (request.cid.isNullOrBlank()) {
          throw IllegalArgumentException("missing cid")
        }
        heosClient.retrieveMetadata(request.sid, request.cid)
      }

  override fun setServiceOption(request: SetServiceOptionRequest?,
                                responseObserver: StreamObserver<SetServiceOptionResponse>?) =
      callAndObserve(executor, responseObserver!!, { heosClient.reconnect() }) {
        val range = request!!.valuesMap["range"]?.let { range ->
          Try.of {
            val (start, end) = range.split(",").map { it.trim().toInt() }
            IntRange(start, end)
          }.getOrElseThrow(Supplier {
            IllegalArgumentException("range should be of format `start,end`")
          })
        } ?: HeosClient.DEFAULT_RANGE
        heosClient.setServiceOption(request.option,
            AttributesBuilder()
                .add(request.valuesMap.filter { it.key != "range" })
                .build(),
            range)
      }
}
