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

data class HeosCommandException(val eid: Int, val text: String) : Exception(text) {
  companion object {
    fun build(message: Message) =
        HeosCommandException(message.value("eid")?.toInt() ?: -1,
            message.value("text") ?: "no error message")
  }
}

enum class ErrorCode {
  SUCCESS,
  UNRECOGNIZED_COMMAND,
  INVALID_ID,
  WROING_NUMBER_OF_COMMAND_ARGUMENTS,
  REQUESTED_DATA_NOT_AVAILABLE,
  RESOURCE_CURRENTLY_NOT_AVAILABLE,
  INVALID_CREDENTIALS,
  COMMAND_COULD_NOT_BE_EXECUTED,
  USER_NOT_LOGGED_IN,
  PARAMETER_OUT_OF_RANGE,
  USER_NOT_FOUND,
  INTERNAL_ERROR,
  SYSTEM_ERROR,
  PROCESSING_PREVIOUS_COMMAND,
  MEDIA_CANNOT_BE_PLAYED,
  OPTION_NO_SUPPORTED
}
