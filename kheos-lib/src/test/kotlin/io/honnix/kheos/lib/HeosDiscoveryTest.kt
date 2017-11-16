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

import io.kotlintest.matchers.*
import io.kotlintest.mock.*
import io.kotlintest.specs.StringSpec
import org.fourthline.cling.UpnpService
import org.fourthline.cling.controlpoint.ControlPoint
import org.fourthline.cling.model.message.header.DeviceTypeHeader
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.registry.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify

class HeosRegistryListenerTest : StringSpec() {
  init {
    "should invoke callback" {
      val device = mock<RemoteDevice>()
      `when`(device.type).thenReturn(HeosDiscovery.deviceType)
      val list = mutableListOf<RemoteDevice>()

      val registryListener = HeosDiscovery.HeosRegistryListener({ list.add(it) })
      registryListener.remoteDeviceAdded(mock<Registry>(), device)

      list shouldBe listOf(device)
    }

    "should not invoke callback" {
      val device = mock<RemoteDevice>()
      `when`(device.type).thenReturn(null)
      val list = mutableListOf<RemoteDevice>()

      val registryListener = HeosDiscovery.HeosRegistryListener({ list.add(it) })
      registryListener.remoteDeviceAdded(mock<Registry>(), device)

      list shouldNotBe listOf(device)
    }
  }
}

class HeosDiscoveryTest : StringSpec() {
  private val upnpService = mock<UpnpService>()
  private val heosDiscovery = HeosDiscovery({ }, upnpService)

  init {
    "should discover" {
      val controlPoint = mock<ControlPoint>()
      val registry = mock<Registry>()
      `when`(upnpService.controlPoint).thenReturn(controlPoint)
      `when`(controlPoint.registry).thenReturn(registry)

      heosDiscovery.discover()

      verify(registry).addListener(any<RegistryListener>())
      verify(controlPoint).search(any<DeviceTypeHeader>())
    }

    "should close" {
      heosDiscovery.close()
      verify(upnpService).shutdown()
    }
  }
}

/**
 * An example shows how to use this class
 */
fun main(args: Array<String>) {
  val d = HeosDiscovery({
    println("${it.displayString}: ${it.identity.descriptorURL.host}")
  })

  d.discover()

  Runtime.getRuntime().addShutdownHook(Thread {
    d.close()
  })
}
