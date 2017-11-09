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

import com.spotify.apollo.AppInit
import com.spotify.apollo.Environment
import com.spotify.apollo.httpservice.HttpService
import com.spotify.apollo.route.Route
import io.honnix.kheos.lib.HeosClient
import java.io.Closeable

class KheosApp(private val heosClientFactory: (String) -> HeosClient) : AppInit {
  override fun create(environment: Environment) {
    val config = environment.config()

    val heosHost = config.getString("kheos.heos.host")
    val heosClient = heosClientFactory(heosHost)

    if (config.getBoolean("kheos.enable.heartbeat")) {
      val interval = environment.config().getLong("kheos.heartbeat.interval.in.second")
      heosClient.startHeartbeat(0, interval)
      environment.closer().register(Closeable {
        heosClient.stopHeartbeat()
        heosClient.close()
      })
    } else {
      environment.closer().register(Closeable { heosClient.close() })
    }

    val heosSystemCommandResource = HeosSystemCommandResource(heosClient)
    val heosPlayerCommandResource = HeosPlayerCommandResource(heosClient)

    environment.routingEngine()
        .registerAutoRoute(Route.sync("GET", "/ping") { "pong" })
        .registerRoutes(heosSystemCommandResource.routes().stream())
        .registerRoutes(heosPlayerCommandResource.routes().stream())
  }
}

fun main(args: Array<String>) {
  HttpService.boot(KheosApp({ HeosClient.newInstance(it) }), "kheos-service", *args)
}
