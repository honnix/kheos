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
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.honnix.kheos.lib.JSON.Str2GroupedCommandConverter
import io.honnix.kheos.lib.JSON.Str2MessageConverter

data class GroupedCommand(val group: CommandGroup, val command: Command) {
  @JsonValue
  override fun toString() = "${group.group}/${command.command}"
}

enum class Command(val command: String) {
  HEART_BEAT("heart_beat"),
  CHECK_ACCOUNT("check_account"),
  SIGN_IN("sign_in"),
  SIGN_OUT("sign_out");

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

data class Message(private val content: Map<String, List<String>>) {
  constructor() : this(mapOf())

  class Builder {
    private var map = mutableMapOf<String, List<String>>()

    fun add(name: String): Builder {
      add(name, listOf())
      return this
    }

    fun add(name: String, value: String): Builder {
      add(name, listOf(value))
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

  fun isEmpty() = content.isEmpty()

  fun isNotEmpty() = content.isNotEmpty()

  @JsonValue
  override fun toString() = content.entries.joinToString("&") { entry ->
    if (entry.value.isEmpty()) entry.key
    else entry.value.joinToString("&") { value -> "${entry.key}=$value" }
  }
}

typealias Attributes = Message
typealias AttributesBuilder = Message.Builder

data class Status(@JsonDeserialize(converter = Str2GroupedCommandConverter::class)
                  val command: GroupedCommand,
                  val result: Result,
                  @JsonDeserialize(converter = Str2MessageConverter::class)
                  val message: Message)

interface GenericResponse {
  val status: Status
}

data class HeartbeatResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class CheckAccountResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SignInResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class SignOutResponse(@JsonProperty("heos") override val status: Status) : GenericResponse
