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

data class GroupedCommand(val group: CommandGroup, val command: Command) {
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

  override fun toString() = command
}

enum class CommandGroup(val group: String) {
  SYSTEM("system"),
  PLAYER("player"),
  GROUP("group"),
  BROWSE("browse");

  override fun toString() = group
}

enum class RegisterChangeEvents(private val state: String) {
  ON("on"),
  OFF("off");

  override fun toString() = state
}

data class Message(private val content: Map<String, List<String>>) {
  companion object {
    fun from(message: String) =
        if (message.isEmpty()) Message(mapOf())
        else
          Message(message.split("&").map { x ->
            val parts = x.split("=")
            parts[0] to if (parts.size > 1) listOf(parts[1]) else emptyList()
          }.fold(mapOf()) { acc, x ->
            acc + (x.first to acc.getOrElse(x.first) { emptyList() } + x.second)
          })
  }

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
      map.merge(name, value) { x, y -> x + y }
      return this
    }

    fun add(message: Message): Builder {
      message.content.entries.forEach { add(it.key, it.value) }
      return this
    }

    fun add(map: Map<String, String>): Builder {
      map.entries.forEach { add(it.key, it.value) }
      return this
    }

    fun build() = Message(map.toMap())
  }

  fun values(name: String) = content[name]

  fun value(name: String) = values(name)?.firstOrNull()

  fun intValue(name: String) = value(name)?.toInt()

  fun <T> typedValue(name: String, converter: (String) -> T) = value(name)?.let(converter)

  fun isNotEmpty() = content.isNotEmpty()

  fun isEmpty() = !isNotEmpty()

  override fun toString() = content.entries.joinToString("&") { entry ->
    if (entry.value.isEmpty()) entry.key
    else entry.value.joinToString("&") { value -> "${entry.key}=$value" }
  }
}

typealias Attributes = Message
typealias AttributesBuilder = Message.Builder
