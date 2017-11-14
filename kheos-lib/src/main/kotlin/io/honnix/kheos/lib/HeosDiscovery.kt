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

import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.model.message.header.DeviceTypeHeader
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.types.DeviceType
import org.fourthline.cling.registry.*

object HeosDiscovery {
  class MyRegistryListener(private val deviceType: DeviceType) : DefaultRegistryListener() {
    override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
      if (device.type == deviceType) {
        println(device.displayString)
      }
    }
  }

  val deviceType = DeviceType("schemas-denon-com", "ACT-Denon", 1)

  fun search(listener: RegistryListener) {
    val serverThread = Thread {
      val upnpService = UpnpServiceImpl()
      Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
          upnpService.shutdown()
        }
      })

      upnpService.controlPoint.registry.addListener(listener)
      upnpService.controlPoint.search(DeviceTypeHeader(deviceType))
    }

    serverThread.isDaemon = false
    serverThread.start()
  }
}
