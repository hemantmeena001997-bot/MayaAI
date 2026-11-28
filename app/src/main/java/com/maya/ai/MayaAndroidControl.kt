package com.maya.ai

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object MayaAndroidControl {

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun getStatus(): String {
        return when {
            !isShizukuRunning() ->
                "Shizuku Service चालू नहीं है।"

            !hasShizukuPermission() ->
                "MAYA को Shizuku Permission नहीं मिली।"

            else ->
                "MAYA Android Control तैयार है।"
        }
    }
    fun getDeviceInfo(): String {
        return """
            फोन: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            Android Version: ${android.os.Build.VERSION.RELEASE}
            SDK: ${android.os.Build.VERSION.SDK_INT}
            Shizuku: ${getStatus()}
        """.trimIndent()
    }
}
