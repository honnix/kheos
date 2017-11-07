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
import io.kotlintest.properties.forAll
import io.kotlintest.specs.StringSpec

class HeosCommandExceptionTest : StringSpec({
  "should build correct exception from message" {
    forAll({ eid: Int, text: String ->
      HeosCommandException.build(
          Message.Builder()
              .add("eid", eid.toString())
              .add("text", text)
              .build()
      ) == HeosCommandException(ErrorId.from(eid), text)
    })
  }

  "should build correct exception with fallback eid and test from message" {
    HeosCommandException.build(Message()) shouldBe
        HeosCommandException(ErrorId.UNKNOWN, "no error message")
  }
})
