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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.StdConverter
import com.fasterxml.jackson.module.kotlin.KotlinModule

object JSON {
  val mapper = ObjectMapper().registerModule(KotlinModule())!!

  class Str2CommandConverter : StdConverter<String, GroupedCommand>() {
    override fun convert(value: String): GroupedCommand {
      val parts = value.split("/")
      return GroupedCommand(CommandGroup.from(parts[0]), Command.from(parts[1]))
    }
  }

  class Str2MessageConverter : StdConverter<String, Message>() {
    override fun convert(value: String): Message {
      return if (value.isEmpty()) Message(mapOf())
      else
        Message(value.split("&").map { x ->
          val parts = x.split("=")
          parts[0] to if (parts.size > 1) listOf(parts[1]) else listOf()
        }.fold(mapOf(), { acc, x ->
          acc + (x.first to acc.getOrElse(x.first, { listOf() }) + x.second)
        }))
    }
  }
}
