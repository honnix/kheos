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

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec
import java.net.URL

class JSONTest : StringSpec({
  "should convert string to command" {
    JSON.Str2GroupedCommandConverter().convert("system/heart_beat") shouldBe
        GroupedCommand(CommandGroup.SYSTEM, Command.HEART_BEAT)
  }

  "should convert empty string to empty message" {
    JSON.Str2MessageConverter().convert("") shouldBe Message()
  }

  "should convert key=value to message" {
    JSON.Str2MessageConverter().convert("foo=bar") shouldBe
        Message.Builder().add("foo", "bar").build()
  }

  "should convert multiple key=value to message" {
    JSON.Str2MessageConverter().convert("foo=bar&foo1=bar1") shouldBe
        Message.Builder()
            .add("foo", "bar")
            .add("foo1", "bar1")
            .build()
  }

  "should convert multiple key=value with duplicated key to message" {
    JSON.Str2MessageConverter().convert("foo=bar&foo=bar1&foo=bar2") shouldBe
        Message.Builder()
            .add("foo", listOf("bar", "bar1"))
            .add("foo", "bar2")
            .build()
  }

  "should convert key to message" {
    JSON.Str2MessageConverter().convert("foo") shouldBe
        Message.Builder().add("foo").build()
  }

  "should convert multiple keys to message" {
    JSON.Str2MessageConverter().convert("foo&bar") shouldBe
        Message.Builder()
            .add("foo")
            .add("bar")
            .build()
  }

  "should convert combination to message" {
    JSON.Str2MessageConverter().convert("foo&foo1=bar") shouldBe
        Message.Builder()
            .add("foo")
            .add("foo1", "bar")
            .build()
  }

  "should convert empty string to null" {
    JSON.Str2URLConverter().convert("") shouldBe null
  }

  "should convert string to URL" {
    JSON.Str2URLConverter().convert("http://example.com") shouldBe
        URL("http://example.com")
  }

  "should serialize and deserialize" {
    val origin = CheckAccountResponse(
        Heos(GroupedCommand(CommandGroup.SYSTEM, Command.CHECK_ACCOUNT),
            Result.SUCCESS, Message()))
    JSON.deserialize<CheckAccountResponse>(JSON.serialize(origin)) shouldBe origin
  }
})
