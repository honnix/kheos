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
package io.honnix.kheos.common

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.honnix.kheos.common.JSON.Str2GroupedCommandConverter
import io.honnix.kheos.common.JSON.Str2MessageConverter
import io.honnix.kheos.common.JSON.Str2URLConverter
import java.net.URL

data class GroupedCommand(val group: CommandGroup, val command: Command) {
  @JsonValue
  override fun toString() = "${group.group}/${command.command}"
}

enum class Command(val command: String) {
  REGISTER_FOR_CHANGE_EVENTS("register_for_change_events"),
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
  GET_SOURCE_INFO("get_source_info"),
  BROWSE("browse"),
  GET_SEARCH_CRITERIA("get_search_criteria"),
  SEARCH("search"),
  PLAY_STREAM("play_stream"),
  PLAY_INPUT("play_input"),
  ADD_TO_QUEUE("add_to_queue"),
  RENAME_PLAYLIST("rename_playlist"),
  DELETE_PLAYLIST("delete_playlist"),
  RETRIEVE_METADATA("retrieve_metadata"),
  GET_SERVICE_OPTIONS("get_service_options"),
  SET_SERVICE_OPTION("set_service_option");

  companion object {
    @JsonCreator
    fun from(command: String) = Command.valueOf(command.toUpperCase())
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
    fun from(state: String) = PlayState.valueOf(state.toUpperCase())
  }

  override fun toString() = state
}

enum class MuteState(private val state: String) {
  ON("on"),
  OFF("off");

  companion object {
    fun from(state: String) = MuteState.valueOf(state.toUpperCase())
  }

  override fun toString() = state
}

enum class PlayRepeatState(private val state: String) {
  ON_ALL("on_all"),
  ON_ONE("on_one"),
  OFF("off");

  companion object {
    fun from(state: String) = PlayRepeatState.valueOf(state.toUpperCase())
  }

  override fun toString() = state
}

enum class PlayShuffleState(private val state: String) {
  ON("on"),
  OFF("off");

  companion object {
    fun from(state: String) = PlayShuffleState.valueOf(state.toUpperCase())
  }

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

enum class AddCriteriaId(val id: Int) {
  UNKNOWN(0),
  PLAY_NOW(1),
  PLAY_NEXT(2),
  ADD_TO_END(3),
  REPLACE_AND_PLAY(4);

  override fun toString() = id.toString()
}

enum class RegisterChangeEvents(private val state: String) {
  ON("on"),
  OFF("off");

  override fun toString() = state
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

    fun add(message: Message): Builder {
      message.content.entries.forEach { add(it.key, it.value) }
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

typealias Attributes = Message
typealias AttributesBuilder = Message.Builder
typealias Options = List<Map<String, List<Option>>>

data class Option(val id: Int, val name: String) {
  companion object {
    val ADD_TRACK_TO_LIBRARY = Option(1, "Add Track to Library")

    val ADD_ALBUM_TO_LIBRARY = Option(2, "Add Album to Library")

    val ADD_STATION_TO_LIBRARY = Option(3, "Add Station to Library")

    val ADD_PLAYLIST_TO_LIBRARY = Option(4, "Add Playlist to Library")

    val REMOVE_TRACK_FROM_LIBRARY = Option(5, "Remove Track from Library")

    val REMOVE_ALBUM_FROM_LIBRARY = Option(6, "Remove Album from Library")

    val REMOVE_STATION_FROM_LIBRARY = Option(7, "Remove Station from Library")

    val REMOVE_PLAYLIST_FROM_LIBRARY = Option(8, "Remove Playlist from Library")

    val THUMBS_UP = Option(11, "Thumbs Up")

    val THUMBS_DOWN = Option(12, "Thumbs Down")

    val CREATE_NEW_STATION = Option(13, "Create New Station")

    val ADD_TO_HEOS_FAVORITES = Option(19, "Add to HEOS Favorites")

    val REMOVE_FROM_HEOS_FAVORITES = Option(20, "Remove from HEOS Favorites")
  }
}

data class Heos(@JsonDeserialize(converter = Str2GroupedCommandConverter::class)
                val command: GroupedCommand,
                val result: Result,
                @JsonDeserialize(converter = Str2MessageConverter::class)
                val message: Message)

data class Player(val name: String, val pid: String,
                  val model: String, val version: String, val ip: String,
                  val network: String, val lineout: Lineout, val serial: String,
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

data class Image(@JsonDeserialize(converter = Str2URLConverter::class)
                 val imageUrl: URL?,
                 val width: Double)

data class Metadata(val albumId: String, val images: List<Image>)

interface GenericResponse {
  val heos: Heos
}

data class HeartbeatResponse(override val heos: Heos) : GenericResponse

data class CheckAccountResponse(override val heos: Heos) : GenericResponse

data class SignInResponse(override val heos: Heos) : GenericResponse

data class SignOutResponse(override val heos: Heos) : GenericResponse

data class RebootResponse(override val heos: Heos) : GenericResponse

data class GetPlayersResponse(override val heos: Heos,
                              val payload: List<Player>) : GenericResponse

data class GetPlayerInfoResponse(override val heos: Heos,
                                 val payload: Player) : GenericResponse

data class GetPlayStateResponse(override val heos: Heos) : GenericResponse

data class SetPlayStateResponse(override val heos: Heos) : GenericResponse

data class GetNowPlayingMediaResponse(override val heos: Heos,
                                      val payload: NowPlayingMedia,
                                      val options: Options = emptyList()) : GenericResponse

data class GetVolumeResponse(override val heos: Heos) : GenericResponse

data class SetVolumeResponse(override val heos: Heos) : GenericResponse

data class VolumeUpResponse(override val heos: Heos) : GenericResponse

data class VolumeDownResponse(override val heos: Heos) : GenericResponse

data class GetMuteResponse(override val heos: Heos) : GenericResponse

data class SetMuteResponse(override val heos: Heos) : GenericResponse

data class ToggleMuteResponse(override val heos: Heos) : GenericResponse

data class GetPlayModeResponse(override val heos: Heos) : GenericResponse

data class SetPlayModeResponse(override val heos: Heos) : GenericResponse

data class GetQueueResponse(override val heos: Heos,
                            val payload: List<QueueItem>) : GenericResponse

data class PlayQueueResponse(override val heos: Heos) : GenericResponse

data class RemoveFromQueueResponse(override val heos: Heos) : GenericResponse

data class SaveQueueResponse(override val heos: Heos) : GenericResponse

data class ClearQueueResponse(override val heos: Heos) : GenericResponse

data class PlayNextResponse(override val heos: Heos) : GenericResponse

data class PlayPreviousResponse(override val heos: Heos) : GenericResponse

data class GetGroupsResponse(override val heos: Heos,
                             val payload: List<Group>) : GenericResponse

data class GetGroupInfoResponse(override val heos: Heos,
                                val payload: Group) : GenericResponse

data class SetGroupResponse(override val heos: Heos) : GenericResponse

data class DeleteGroupResponse(override val heos: Heos) : GenericResponse

data class GetMusicSourcesResponse(override val heos: Heos,
                                   val payload: List<MusicSource>) : GenericResponse

data class GetMusicSourceInfoResponse(override val heos: Heos,
                                      val payload: MusicSource) : GenericResponse

data class BrowseMediaSourcesResponse(override val heos: Heos,
                                      val payload: List<MusicSource>,
                                      val options: Options = emptyList()) : GenericResponse

data class BrowseTopMusicResponse(override val heos: Heos,
                                  val payload: List<Media>) : GenericResponse

data class BrowseSourceContainersResponse(override val heos: Heos,
                                          val payload: List<Media>,
                                          val options: Options = emptyList()) : GenericResponse

data class GetSearchCriteriaResponse(override val heos: Heos,
                                     val payload: List<SearchCriteria>) : GenericResponse

data class SearchResponse(override val heos: Heos,
                          val payload: List<Media>) : GenericResponse

data class PlayStreamResponse(override val heos: Heos) : GenericResponse

data class PlayInputResponse(override val heos: Heos) : GenericResponse

data class AddToQueueResponse(override val heos: Heos) : GenericResponse

data class RenamePlaylistResponse(override val heos: Heos) : GenericResponse

data class DeletePlaylistResponse(override val heos: Heos) : GenericResponse

data class RetrieveMetadataResponse(override val heos: Heos,
                                    val payload: List<Metadata>) : GenericResponse

data class GetServiceOptionsResponse(override val heos: Heos,
                                     val payload: Options) : GenericResponse

data class SetServiceOptionResponse(override val heos: Heos) : GenericResponse

data class RegisterForChangeEventsResponse(override val heos: Heos) : GenericResponse

enum class ChangeEventCommand(private val command: String) {
  PLAYER_NOW_PLAYING_PROGRESS("player_now_playing_progress"),
  PLAYER_NOW_PLAYING_CHANGED("player_now_playing_changed"),
  PLAYER_STATE_CHANGED("player_state_changed"),
  PLAYER_QUEUE_CHANGED("player_queue_changed"),
  PLAYER_VOLUME_CHANGED("player_volume_changed"),
  PLAYER_MUTE_CHANGED("player_mute_changed"),
  REPEAT_MODE_CHANGED("repeat_mode_changed"),
  SHUFFLE_MODE_CHANGED("shuffle_mode_changed"),
  GROUP_CHANGED("group_changed"),
  GROUP_VOLUME_CHANGED("group_volume_changed"),
  GROUP_MUTE_CHANGED("group_mute_changed"),
  USER_CHANGED("user_changed");

  companion object {
    @JsonCreator
    fun from(command: String) = ChangeEventCommand.valueOf(
        command.split("/").last().toUpperCase())
  }

  @JsonValue
  override fun toString() = "event/$command"
}

data class ChangeEvent(val command: ChangeEventCommand,
                       @JsonDeserialize(converter = Str2MessageConverter::class)
                       val message: Message)

data class ChangeEventResponse(@JsonProperty("heos") val event: ChangeEvent)
