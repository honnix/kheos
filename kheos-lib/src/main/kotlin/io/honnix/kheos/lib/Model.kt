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
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import io.honnix.kheos.lib.JSON.GroupedCommand2StrConverter
import io.honnix.kheos.lib.JSON.Message2StrConverter
import io.honnix.kheos.lib.JSON.Str2GroupedCommandConverter
import io.honnix.kheos.lib.JSON.Str2MessageConverter

data class GroupedCommand(val group: CommandGroup, val command: Command) {
  override fun toString(): String {
    return "${group.group}/${command.command}"
  }
}

enum class Command(val command: String) {
  HEART_BEAT("heart_beat"),
  CHECK_ACCOUNT("check_account");

  @JsonValue
  override fun toString() = command

  companion object {
    @JsonCreator
    fun from(command: String) = Command.valueOf(command.toUpperCase())
  }
}

enum class CommandGroup(val group: String) {
  SYSTEM("system"),
  PLAYER("player"),
  GROUP("group"),
  BROWSE("browse");

  @JsonValue
  override fun toString() = group

  companion object {
    @JsonCreator
    fun from(group: String) = CommandGroup.valueOf(group.toUpperCase())
  }
}

enum class Result(private val status: String) {
  SUCCESS("success"),
  FAIL("fail");

  @JsonValue
  override fun toString() = status

  companion object {
    @JsonCreator
    fun from(status: String) = Result.valueOf(status.toUpperCase())
  }
}

data class Message(private val content: Map<String, List<String>>) {
  fun values(name: String): List<String>? = content[name]

  fun value(name: String): String? {
    return values(name)?.firstOrNull()
  }

  fun names() = content.keys
}

data class Status(@JsonDeserialize(converter = Str2GroupedCommandConverter::class)
                  @JsonSerialize(converter = GroupedCommand2StrConverter::class)
                  val command: GroupedCommand,
                  val result: Result,
                  @JsonDeserialize(converter = Str2MessageConverter::class)
                  @JsonSerialize(converter = Message2StrConverter::class)
                  val message: Message)

interface GenericResponse {
  val status: Status
}

data class HeartbeatResponse(@JsonProperty("heos") override val status: Status) : GenericResponse

data class CheckAccountResponse(@JsonProperty("heos") override val status: Status) : GenericResponse
