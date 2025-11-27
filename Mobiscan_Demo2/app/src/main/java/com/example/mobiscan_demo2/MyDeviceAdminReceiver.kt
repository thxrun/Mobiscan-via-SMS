package com.example.mobiscan_demo2

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver(){
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "Device Admin Disabled")
    }
}
