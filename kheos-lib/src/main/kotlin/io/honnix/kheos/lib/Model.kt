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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.honnix.kheos.lib.JSON.Str2GroupedCommandConverter
import io.honnix.kheos.lib.JSON.Str2MessageConverter
import io.honnix.kheos.lib.JSON.Str2URLConverter
import java.net.URL

data class GroupedCommand(val group: CommandGroup, val command: Command) {
  @JsonValue
  override fun toString() = "${group.group}/${command.command}"
}

enum class Command(val command: String) {
  HEART_BEAT("heart_beat"),
  CHECK_ACCOUNT("check_account"),
  SIGN_IN("sign_in"),
  SIGN_OUT("sign_out"),
  REBOOT("reboot"),
  GET_PLAYERS("get_players"),
  GET_PLAYER_INFO("get_player_info"),
  GET_PLAY_STATE("get_play_state"),
  SET_PLAY_STATE("set_play_state"),
  GET_NOW_PLAYING_MEDIA("get_now_playing_media"),
  GET_VOLUME("get_volume"),
  SET_VOLUME("set_volume"),
  VOLUME_UP("volume_up"),
  VOLUME_DOWN("volume_down"),
  GET_MUTE("get_mute"),
  SET_MUTE("set_mute"),
  TOGGLE_MUTE("toggle_mute"),
  GET_PLAY_MODE("get_play_mode"),
  SET_PLAY_MODE("set_play_mode"),
  GET_QUEUE("get_queue"),
  PLAY_QUEUE("play_queue"),
  REMOVE_FROM_QUEUE("remove_from_queue"),
  SAVE_QUEUE("save_queue"),
  CLEAR_QUEUE("clear_queue"),
  PLAY_NEXT("play_next"),
  PLAY_PREVIOUS("play_previous"),
  GET_GROUPS("get_groups"),
  GET_GROUP_INFO("get_group_info"),
  SET_GROUP("set_group"),
  GET_MUSIC_SOURCES("get_music_sources"),
  GET_MUSIC_SOURCE_INFO("get_source_info"),
  BROWSE("browse"),
  GET_SEARCH_CRITERIA("get_search_criteria"),
  SEARCH("search"),
  PLAY_STREAM("play_stream"),
  PLAY_INPUT("play_input"),
  ADD_TO_QUEUE("add_to_queue");

  companion object {
    @JsonCreator
    fun from(command: String) =
        if (command != "get_source_info") Command.valueOf(command.toUpperCase())
        else GET_MUSIC_SOURCE_INFO
  }

  @JsonValue
  override fun toString() = command
}

enum class CommandGroup(val group: String) {
  SYSTEM("system"),
  PLAYER("player"),
  GROUP("group"),
  BROWSE("browse");

  companion object {
    @JsonCreator
    fun from(group: String) = CommandGroup.valueOf(group.toUpperCase())
  }

  @JsonValue
  override fun toString() = group
}

enum class Result(private val status: String) {
  SUCCESS("success"),
  FAIL("fail");

  companion object {
    @JsonCreator
    fun from(status: String) = Result.valueOf(status.toUpperCase())
  }

  @JsonValue
  override fun toString() = status
}

enum class Lineout(@JsonValue val type: Int) {
  UNKNOWN(0),
  VARIABLE(1),
  FIXED(2);

  companion object {
    @JsonCreator
    fun from(type: Int) = Lineout.values().find { x -> x.type == type } ?: UNKNOWN
  }

  override fun toString() = type.toString()
}

enum class Control(@JsonValue val option: Int) {
  UNKNOWN(0),
  NONE(1),
  IR(2),
  TRIGGER(3),
  NETWORK(4);

  companion object {
    @JsonCreator
    fun from(option: Int) = Control.values().find { x -> x.option == option } ?: UNKNOWN
  }

  override fun toString() = option.toString()
}

enum class PlayState(private val state: String) {
  PLAY("play"),
  PAUSE("pause"),
  STOP("Stop");

  companion object {
    @JsonCreator
    fun from(state: String) = PlayState.valueOf(state.toUpperCase())
  }

  @JsonValue
  override fun toString() = state
}

enum class MuteState(private val state: String) {
  ON("on"),
  OFF("off");

  companion object {
    @JsonCreator
    fun from(state: String) = MuteState.valueOf(state.toUpperCase())
  }

  @JsonValue
  override fun toString() = state
}

enum class PlayRepeatState(private val state: String) {
  ON("on"),
  OFF("off");

  companion object {
    @JsonCreator
    fun from(state: String) = PlayRepeatState.valueOf(state.toUpperCase())
  }

  @JsonValue
  override fun toString() = state
}

enum class PlayShuffleState(private val state: String) {
  ON("on"),
  OFF("off");

  companion object {
    @JsonCreator
    fun from(state: String) = PlayShuffleState.valueOf(state.toUpperCase())
  }

  @JsonValue
  override fun toString() = state
}

enum class MediaType(private val type: String) {
  ARTIST("artist"),
  ALBUM("album"),
  SONG("song"),
  GENRE("genre"),
  CONTAINER("container"),
  STATION("station");

  companion object {
    @JsonCreator
    fun from(type: String) = MediaType.valueOf(type.toUpperCase())
  }

  @JsonValue
  override fun toString() = type
}

enum class Role(private val role: String) {
  LEADER("leader"),
  MEMBER("member");

  companion object {
    @JsonCreator
    fun from(role: String) = Role.valueOf(role.toUpperCase())
  }

  @JsonValue
  override fun toString() = role
}

enum class MusicSourceType(private val type: String) {
  MUSIC_SERVICE("music_service"),
  HEOS_SERVICE("heos_service"),
  HEOS_SERVER("heos_server"),
  DLNA_SERVER("dlna_server");

  companion object {
    @JsonCreator
    fun from(type: String) = MusicSourceType.valueOf(type.toUpperCase())
  }

  @JsonValue
  override fun toString() = type
}

enum class YesNo(private val value: String) {
  YES("yes"),
  NO("no");

  companion object {
    @JsonCreator
    fun from(value: String) = YesNo.valueOf(value.toUpperCase())
  }

  @JsonValue
  override fun toString() = value
}

enum class AddCriteriaId(@JsonValue val id: Int) {
  UNKNOWN(0),
  PLAY_NOW(1),
  PLAY_NEXT(2),
  ADD_TO_END(3),
  REPLACE_AND_PLAY(4);

  companion object {
    @JsonCreator
    fun from(id: Int) = AddCriteriaId.values().find { x -> x.id == id } ?: UNKNOWN
  }

  override fun toString() = id.toString()
}

data class Message(private val content: Map<String, List<String>>) {
  constructor() : this(mapOf())

  class Builder {
    private var map = mutableMapOf<String, List<String>>()

    fun add(name: String): Builder {
      add(name, emptyList())
      return this
    }

    fun add(name: String, value: String): Builder {
      add(name, listOf(value))
      return this
    }

    fun add(name: String, value: Any): Builder {
      add(name, value.toString())
      return this
    }

    fun add(name: String, condition: () -> Boolean, value: () -> Any): Builder {
      if (condition()) {
        add(name, value())
      }
      return this
    }

    fun add(name: String, value: List<String>): Builder {
      map.merge(name, value, { x, y -> x + y })
      return this
    }

    fun build() = Message(map.toMap())
  }

  fun values(name: String) = content[name]

  fun value(name: String) = values(name)?.firstOrNull()

  fun intValue(name: String) = value(name)?.toInt()

  fun <T : Enum<T>> enumValue(name: String, valueOf: (String) -> Enum<T>) =
      value(name)?.let { valueOf(it.toUpperCase()) }

  fun <T> typedValue(name: String, converter: (String) -> T) = value(name)?.let(converter)

  fun isNotEmpty() = content.isNotEmpty()

  fun isEmpty() = !isNotEmpty()

  @JsonValue
  override fun toString() = content.entries.joinToString("&") { entry ->
    if (entry.value.isEmpty()) entry.key
    else entry.value.joinToString("&") { value -> "${entry.key}=$value" }
  }
}

data class Option(val id: Int, val name: String)

typealias Attributes = Message
typealias AttributesBuilder = Message.Builder
typealias Options = List<Map<String, List<Option>>>

data class Status(@JsonDeserialize(converter = Str2GroupedCommandConverter::class)
                  val command: GroupedCommand,
                  val result: Result,
                  @JsonDeserialize(converter = Str2MessageConverter::class)
                  val message: Message)

data class Player(val name: String, val pid: String,
                  val model: String, val version: String, val ip: String,
                  val network: String, val lineout: Lineout,
                  val gid: String? = null, val control: Control? = null)

data class NowPlayingMedia(val type: MediaType, val song: String,
                           val album: String, val artist: String,
                           @JsonDeserialize(converter = Str2URLConverter::class) val imageUrl: URL?,
                           val albumId: String, val mid: String, val qid: String,
                           val sid: String, val station: String? = null)

data class QueueItem(val song: String,
                     val album: String, val artist: String,
                     @JsonDeserialize(converter = Str2URLConverter::class) val imageUrl: URL?,
                     val qid: String, val mid: String,
                     val albumId: String)

data class GroupedPlayer(val name: String, val pid: String, val role: Role)

data class Group(val name: String, val gid: String, val players: List<GroupedPlayer>)

data class MusicSource(val name: String,
                       @JsonDeserialize(converter = Str2URLConverter::class) val imageUrl: URL?,
                       val type: MusicSourceType,
                       val sid: String)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = MediaArtist::class, name = "artist"),
    JsonSubTypes.Type(value = MediaAlbum::class, name = "album"),
    JsonSubTypes.Type(value = MediaSong::class, name = "song"),
    JsonSubTypes.Type(value = MediaGenre::class, name = "genre"),
    JsonSubTypes.Type(value = MediaContainer::class, name = "container"),
    JsonSubTypes.Type(value = MediaStation::class, name = "station"))
interface Media {
  val container: YesNo
  val playable: YesNo
  val type: MediaType
  val name: String
  val imageUrl: URL?
  val mid: String
}

data class MediaArtist(override val container: YesNo,
                       override val playable: YesNo,
                       override val type: MediaType,
                       override val name: String,
                       @JsonDeserialize(converter = Str2URLConverter::class)
                       override val imageUrl: URL?,
                       val cid: String,
                       override val mid: String) : Media

data class MediaAlbum(override val container: YesNo,
                      override val playable: YesNo,
                      override val type: MediaType,
                      override val name: String,
                      @JsonDeserialize(converter = Str2URLConverter::class)
                      override val imageUrl: URL?,
                      val artist: String,
                      val cid: String,
                      override val mid: String) : Media

data class MediaSong(override val container: YesNo,
                     override val playable: YesNo,
                     override val type: MediaType,
                     override val name: String,
                     @JsonDeserialize(converter = Str2URLConverter::class)
                     override val imageUrl: URL?,
                     val artist: String,
                     val album: String,
                     override val mid: String) : Media

data class MediaGenre(override val container: YesNo,
                      override val playable: YesNo,
                      override val type: MediaType,
                      override val name: String,
                      @JsonDeserialize(converter = Str2URLConverter::class)
                      override val imageUrl: URL?,
                      val cid: String,
                      override val mid: String) : Media

data class MediaContainer(override val container: YesNo,
                          override val playable: YesNo,
                          override val type: MediaType,
                          override val name: String,
                          @JsonDeserialize(converter = Str2URLConverter::class)
                          override val imageUrl: URL?,
                          val cid: String,
                          override val mid: String) : Media

data class MediaStation(override val container: YesNo,
                        override val playable: YesNo,
                        override val type: MediaType,
                        override val name: String,
                        @JsonDeserialize(converter = Str2URLConverter::class)
                        override val imageUrl: URL?,
                        override val mid: String) : Media

data class SearchCriteria(val name: String, val scid: Int, val wildcard: YesNo)

interface GenericResponse {
  val status: Status
}

data class HeartbeatResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class CheckAccountResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SignInResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SignOutResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class RebootResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetPlayersResponse(@JsonProperty("heos") override val status: Status,
                              val payload: List<Player>) : GenericResponse

data class GetPlayerInfoResponse(@JsonProperty("heos") override val status: Status,
                                 val payload: Player) : GenericResponse

data class GetPlayStateResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SetPlayStateResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetNowPlayingMediaResponse(@JsonProperty("heos") override val status: Status,
                                      val payload: NowPlayingMedia,
                                      val options: Options = emptyList()) : GenericResponse

data class GetVolumeResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SetVolumeResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class VolumeUpResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class VolumeDownResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetMuteResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SetMuteResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class ToggleMuteResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetPlayModeResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SetPlayModeResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetQueueResponse(@JsonProperty("heos") override val status: Status,
                            val payload: List<QueueItem>) : GenericResponse

data class PlayQueueResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class RemoveFromQueueResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SaveQueueResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class ClearQueueResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class PlayNextResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class PlayPreviousResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetGroupsResponse(@JsonProperty("heos") override val status: Status,
                             val payload: List<Group>) : GenericResponse

data class GetGroupInfoResponse(@JsonProperty("heos") override val status: Status,
                                val payload: Group) : GenericResponse

data class SetGroupResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class GetMusicSourcesResponse(@JsonProperty("heos") override val status: Status,
                                   val payload: List<MusicSource>) : GenericResponse

data class GetMusicSourceInfoResponse(@JsonProperty("heos") override val status: Status,
                                      val payload: MusicSource) : GenericResponse

data class BrowseMediaSourcesResponse(@JsonProperty("heos") override val status: Status,
                                      val payload: List<MusicSource>,
                                      val options: Options = emptyList()) : GenericResponse

data class BrowseTopMusicResponse(@JsonProperty("heos") override val status: Status,
                                  val payload: List<Media>) : GenericResponse

data class BrowseSourceContainersResponse(@JsonProperty("heos") override val status: Status,
                                          val payload: List<Media>,
                                          val options: Options = emptyList()) : GenericResponse

data class GetSearchCriteriaResponse(@JsonProperty("heos") override val status: Status,
                                     val payload: List<SearchCriteria>) : GenericResponse

data class SearchResponse(@JsonProperty("heos") override val status: Status,
                          val payload: List<Media>) : GenericResponse

data class PlayStreamResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class PlayInputResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class AddToQueueResponse(@JsonProperty("heos") override val status: Status) : GenericResponse
