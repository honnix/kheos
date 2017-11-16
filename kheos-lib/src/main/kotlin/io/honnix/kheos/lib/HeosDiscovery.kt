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

import org.fourthline.cling.*
import org.fourthline.cling.model.message.header.DeviceTypeHeader
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.types.DeviceType
import org.fourthline.cling.registry.*
import org.slf4j.LoggerFactory
import java.io.Closeable

class HeosDiscovery(private val callback: (RemoteDevice) -> Unit,
                    private val upnpService: UpnpService = UpnpServiceImpl()) : Closeable {
  internal class HeosRegistryListener(private val callback: (RemoteDevice) -> Unit)
    : DefaultRegistryListener() {
    override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
      if (device.type == deviceType) {
        logger.debug("HOES device discovered${device.displayString}")
        callback(device)
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(HeosDiscovery::class.java)

    internal val deviceType = DeviceType("schemas-denon-com", "ACT-Denon", 1)
  }

  fun discover() {
    logger.info("registering device listener")
    upnpService.controlPoint.registry.addListener(HeosRegistryListener(callback))
    logger.info("searching device type $deviceType")
    upnpService.controlPoint.search(DeviceTypeHeader(deviceType))
  }

  override fun close() {
    logger.info("shutting down UPnP service")
    upnpService.shutdown()
  }
}
