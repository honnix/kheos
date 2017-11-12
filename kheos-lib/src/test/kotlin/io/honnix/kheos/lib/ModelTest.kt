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

class MessageTest : StringSpec() {
  init {
    "should get value" {
      val message = Message.Builder()
          .add("pid", "0")
          .build()

      message.value("pid") shouldBe "0"
    }

    "should get enum value" {
      val message = Message.Builder()
          .add("state", MuteState.ON)
          .build()

      message.enumValue("state", { MuteState.valueOf(it) }) shouldBe MuteState.ON
    }

    "should get int value" {
      val message = Message.Builder()
          .add("foo", 100)
          .build()

      message.intValue("foo") shouldBe 100
    }

    "should get typed value" {
      val message = Message.Builder()
          .add("foo", 100)
          .build()

      message.typedValue("foo", { it.toInt() }) shouldBe 100
    }

    "should be empty" {
      Message().isEmpty() shouldBe true
    }

    "should get values" {
      val message = Message.Builder()
          .add("foo", "0")
          .add("foo", "1")
          .build()

      message.values("foo") shouldBe listOf("0", "1")
    }
  }
}
