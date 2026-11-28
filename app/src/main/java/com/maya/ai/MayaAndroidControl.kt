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
}
