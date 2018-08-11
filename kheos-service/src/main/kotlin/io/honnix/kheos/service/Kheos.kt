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
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.ServerBuilder
import io.honnix.kheos.lib.HeosClient
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS;

private data class Environment(val config: Config, val closer: Closer) {
  private val services = mutableListOf<BindableService>()

  fun services() = services.toList()

  fun addServices(vararg services: BindableService) = this.services.addAll(services)
}

private interface AppInit {
  fun create(environment: Environment)
}

private object GrpcServer {
  private val logger = LoggerFactory.getLogger(KheosApp::class.java)

  private lateinit var server: Server

  private const val GRPC_SERVER_PORT = "grpc.server.port"

  val config = ConfigFactory.load()!!
  val closer = Closer.create()!!

  fun boot(app: AppInit) {
    val environment = Environment(config, closer)
    app.create(environment)

    val port = if (config.hasPath(GRPC_SERVER_PORT)) {
      config.getInt(GRPC_SERVER_PORT)
    } else {
      8888
    }

    val serverBuilder = ServerBuilder.forPort(port)
    environment.services().forEach { serverBuilder.addService(it) }
    server = serverBuilder.build().start()
    logger.info("Server started, listening on $port")

    Runtime.getRuntime().addShutdownHook(Thread {
      closer.close()
      System.err.println("*** shutting down gRPC server since JVM is shutting down")
      server.shutdown()
      System.err.println("*** server shut down")
    })

    server.awaitTermination()
  }
}

private class KheosApp(private val heosClientFactory: (String) -> HeosClient) : AppInit {
  companion object {
    val logger = LoggerFactory.getLogger(KheosApp::class.java)

    private val uncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
          logger.error("Thread {} threw {}", thread, throwable)
        }

    private val threadFactory = ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("query-executor-%d")
        .setUncaughtExceptionHandler(uncaughtExceptionHandler)
        .build()

    private fun executorCloser(name: String, executor: ExecutorService): Closeable {
      return Closeable {
        logger.info("Shutting down executor: {}", name)
        executor.shutdown()
        try {
          executor.awaitTermination(1, SECONDS)
        } catch (ignored: InterruptedException) {
        }

        val runnables = executor.shutdownNow()
        if (!runnables.isEmpty()) {
          logger.warn("{} task(s) in {} did not execute", runnables.size, name)
        }
      }
    }
  }

  override fun create(environment: Environment) {
    val config = environment.config
    val closer = environment.closer

    val heosHost = config.getString("kheos.heos.host")
    val heosClient = heosClientFactory(heosHost)

    heosClient.connect()
    closer.register(heosClient)

    if (config.getBoolean("kheos.enable.heartbeat")) {
      val interval = config.getLong("kheos.heartbeat.interval.in.second")
      heosClient.startHeartbeat(interval = interval)
      closer.register(Closeable {
        heosClient.stopHeartbeat()
      })
    }

    val executorService = Executors.newFixedThreadPool(config.getInt("kheos.heos.executor.threads"),
        threadFactory)
    closer.register(executorCloser("heos", executorService))

    environment.addServices(
        HeosSystemService(heosClient, executorService),
        HeosPlayerService(heosClient, executorService),
        HeosGroupService(heosClient, executorService))
  }
}

fun main(args: Array<String>) {
  GrpcServer.boot(KheosApp({ HeosClient.newInstance(it) }))
}
