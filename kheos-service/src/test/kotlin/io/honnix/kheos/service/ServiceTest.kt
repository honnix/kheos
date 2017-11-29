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
package io.honnix.kheos.service

import com.google.common.io.Closer
import com.spotify.apollo.Environment
import com.spotify.apollo.route.*
import com.typesafe.config.Config
import io.honnix.kheos.lib.HeosClient
import io.kotlintest.matchers.shouldBe
import io.kotlintest.mock.`when`
import io.kotlintest.mock.mock
import io.kotlintest.specs.StringSpec
import org.mockito.Mockito.*
import java.io.Closeable

class KheosAppTest : StringSpec() {
  init {
    "should create" {
      val environment = mock<Environment>()
      val config = mock<Config>()
      `when`(config.getString("kheos.heos.host")).thenReturn("heos")
      `when`(config.getBoolean("kheos.enable.heartbeat")).thenReturn(true)
      `when`(config.getLong("kheos.heartbeat.interval.in.second")).thenReturn(5)
      `when`(environment.config()).thenReturn(config)

      val routingEngine = mock<Environment.RoutingEngine>()
      `when`(environment.routingEngine()).thenReturn(routingEngine)
      `when`(routingEngine.registerAutoRoute(any())).thenAnswer({ invocation ->
        val route: Route<AsyncHandler<String>> = invocation.getArgument(0)
        route.handler().invoke(null).toCompletableFuture().get() shouldBe "pong"
        routingEngine
      })
      `when`(routingEngine.registerRoutes(any())).thenReturn(routingEngine)

      val closer = mock<Closer>()
      `when`(environment.closer()).thenReturn(closer)

      `when`(closer.register(any<Closeable>())).thenAnswer({ invocation ->
        val closeable: Closeable = invocation.getArgument(0)
        closeable.close()
        closeable
      })

      val heosClient = mock<HeosClient>()
      KheosApp({ heosClient }).create(environment)

      verify(heosClient).startHeartbeat(interval = 5)
      verify(closer, times(2)).register(any<Closeable>())
      verify(heosClient).stopHeartbeat()
      verify(heosClient).close()

      verify(routingEngine).registerAutoRoute(any())
      verify(routingEngine, times(3)).registerRoutes(any())
    }

    "should create no heartbeat" {
      val environment = mock<Environment>()
      val config = mock<Config>()
      `when`(config.getString("kheos.heos.host")).thenReturn("heos")
      `when`(config.getBoolean("kheos.enable.heartbeat")).thenReturn(false)
      `when`(environment.config()).thenReturn(config)

      val routingEngine = mock<Environment.RoutingEngine>()
      `when`(environment.routingEngine()).thenReturn(routingEngine)
      `when`(routingEngine.registerAutoRoute(any())).thenAnswer({ invocation ->
        val route: Route<AsyncHandler<String>> = invocation.getArgument(0)
        route.handler().invoke(null).toCompletableFuture().get() shouldBe "pong"
        routingEngine
      })
      `when`(routingEngine.registerRoutes(any())).thenReturn(routingEngine)

      val closer = mock<Closer>()
      `when`(environment.closer()).thenReturn(closer)

      `when`(closer.register(any<Closeable>())).thenAnswer({ invocation ->
        val closeable: Closeable = invocation.getArgument(0)
        closeable.close()
        closeable
      })

      val heosClient = mock<HeosClient>()
      KheosApp({ heosClient }).create(environment)

      verify(heosClient, never()).startHeartbeat()
      verify(closer).register(any<Closeable>())
      verify(heosClient, never()).stopHeartbeat()
      verify(heosClient).close()

      verify(routingEngine).registerAutoRoute(any())
      verify(routingEngine, times(3)).registerRoutes(any())
    }
  }
}
