package com.linux_core.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * USB device attach/detach receiver.
 *
 * Registered in AndroidManifest.xml with a device_filter so the system
 * auto-grants USB permission to this app for attached devices (same grant
 * that a manifest intent-filter on an Activity used to provide).
 *
 * Unlike an Activity intent-filter, this receiver does NOT launch any
 * activity — the running TerminalActivity/session is never interrupted.
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                if (device != null) {
                    Log.i(
                        TAG,
                        "USB device ATTACHED: ${device.deviceName} " +
                            "(vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)})"
                    )
                    // device_filter in the manifest already grants permission;
                    // still make sure the runtime cache knows about it.
                    UsbHostManager.onDeviceAttached(device)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                if (device != null) {
                    Log.i(TAG, "USB device DETACHED: ${device.deviceName}")
                    UsbHostManager.onDeviceDetached(device)
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbAttachReceiver"
    }
}
