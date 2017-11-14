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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SNAKE_CASE
import com.fasterxml.jackson.databind.util.StdConverter
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.net.URL

object JSON {
  internal class Str2GroupedCommandConverter : StdConverter<String, GroupedCommand>() {
    override fun convert(value: String): GroupedCommand {
      val parts = value.split("/")
      return GroupedCommand(CommandGroup.from(parts[0]), Command.from(parts[1]))
    }
  }

  internal class Str2MessageConverter : StdConverter<String, Message>() {
    override fun convert(value: String) =
        if (value.isEmpty()) Message(mapOf())
        else
          Message(value.split("&").map { x ->
            val parts = x.split("=")
            parts[0] to if (parts.size > 1) listOf(parts[1]) else emptyList()
          }.fold(mapOf(), { acc, x ->
            acc + (x.first to acc.getOrElse(x.first, { emptyList() }) + x.second)
          }))
  }

  internal class Str2URLConverter : StdConverter<String, URL>() {
    override fun convert(value: String) = if (value.isEmpty()) null else URL(value)
  }

  val mapper: ObjectMapper = ObjectMapper()
      .setPropertyNamingStrategy(SNAKE_CASE)
      .registerModule(KotlinModule())

  fun serialize(value: Any): ByteArray = mapper.writeValueAsBytes(value)

  inline fun <reified T> deserialize(bytes: ByteArray) = mapper.readValue(bytes, T::class.java)
}
