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

data class HeosCommandException(val eid: ErrorId, val text: String) :
    Exception("eid: $eid, text: $text") {
  companion object {
    fun build(message: Message) =
        HeosCommandException(ErrorId.from(message.value("eid")?.toInt()),
            message.value("text") ?: "no error message")
  }
}

enum class ErrorId(val eid: Int) {
  UNKNOWN(0),
  UNRECOGNIZED_COMMAND(1),
  INVALID_ID(2),
  WRONG_NUMBER_OF_COMMAND_ARGUMENTS(3),
  REQUESTED_DATA_NOT_AVAILABLE(4),
  RESOURCE_CURRENTLY_NOT_AVAILABLE(5),
  INVALID_CREDENTIALS(6),
  COMMAND_COULD_NOT_BE_EXECUTED(7),
  USER_NOT_LOGGED_IN(8),
  PARAMETER_OUT_OF_RANGE(9),
  USER_NOT_FOUND(10),
  INTERNAL_ERROR(11),
  SYSTEM_ERROR(12),
  PROCESSING_PREVIOUS_COMMAND(13),
  MEDIA_CANNOT_BE_PLAYED(14),
  OPTION_NO_SUPPORTED(15);

  companion object {
    fun from(eid: Int?) = ErrorId.values().find { x -> x.eid == eid } ?: UNKNOWN
  }
}
