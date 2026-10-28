package com.bleural.croissant

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin entry point. Nothing to override: being the Device Owner is what
 * grants the kiosk policies in [Kiosk]. Provisioned once over adb:
 *
 *     adb shell dpm set-device-owner com.bleural.croissant/.AdminReceiver
 *
 * (refused while any account exists on the device).
 */
class AdminReceiver : DeviceAdminReceiver()
