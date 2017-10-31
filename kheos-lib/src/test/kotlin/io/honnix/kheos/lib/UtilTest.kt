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

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

class JSONTest : StringSpec({
  "should convert string to command" {
    JSON.Str2GroupedCommandConverter().convert("system/heart_beat") shouldBe
        GroupedCommand(CommandGroup.SYSTEM, Command.HEART_BEAT)
  }

  "should convert command to string" {
    JSON.GroupedCommand2StrConverter().convert(
        GroupedCommand(CommandGroup.SYSTEM, Command.HEART_BEAT)) shouldBe "system/heart_beat"
  }

  "should convert empty string to empty message" {
    JSON.Str2MessageConverter().convert("") shouldBe Message(mapOf())
  }

  "should convert key=value to message" {
    JSON.Str2MessageConverter().convert("foo=bar") shouldBe
        Message(mapOf("foo" to listOf("bar")))
  }

  "should convert multiple key=value to message" {
    JSON.Str2MessageConverter().convert("foo=bar&foo1=bar1") shouldBe
        Message(mapOf("foo" to listOf("bar"), "foo1" to listOf("bar1")))
  }

  "should convert multiple key=value with duplicated key to message" {
    JSON.Str2MessageConverter().convert("foo=bar&foo=bar1") shouldBe
        Message(mapOf("foo" to listOf("bar", "bar1")))
  }

  "should convert key to message" {
    JSON.Str2MessageConverter().convert("foo") shouldBe
        Message(mapOf("foo" to listOf()))
  }

  "should convert multiple keys to message" {
    JSON.Str2MessageConverter().convert("foo&bar") shouldBe
        Message(mapOf("foo" to listOf(), "bar" to listOf()))
  }

  "should convert combination to message" {
    JSON.Str2MessageConverter().convert("foo&foo1=bar") shouldBe
        Message(mapOf("foo" to listOf(), "foo1" to listOf("bar")))
  }

  "should convert empty message to empty string" {
    JSON.Message2StrConverter().convert(Message(mapOf())) shouldBe ""
  }

  "should convert message to string" {
    JSON.Message2StrConverter().convert(Message(mapOf(
        "foo" to listOf(),
        "foo1" to listOf("bar1", "bar2"))
    )) shouldBe "foo&foo1=bar1&foo1=bar2"
  }
})
