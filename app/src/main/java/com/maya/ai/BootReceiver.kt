
package com.maya.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            try {
                val serviceIntent = Intent(
                    context,
                    MayaListeningService::class.java
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d("MAYA_BOOT", "MAYA started after reboot")

            } catch (e: Exception) {
                Log.e("MAYA_BOOT", "Start failed", e)
            }
        }
    }
}
