
package com.maya.ai

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
                "MAYA Android Control तैयार नहीं है।"

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

    fun setFlashlight(
        context: Context,
        enabled: Boolean
    ): String {
        return try {
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull {
                val characteristics =
                    cameraManager.getCameraCharacteristics(it)

                characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true &&
                characteristics.get(
                    CameraCharacteristics.LENS_FACING
                ) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return "Flashlight उपलब्ध नहीं है।"

            cameraManager.setTorchMode(cameraId, enabled)

            if (enabled)
                "Flashlight चालू कर दी है।"
            else
                "Flashlight बंद कर दी है।"

        } catch (e: Exception) {
            "Flashlight Control में समस्या: ${e.message}"
        }
    }
}
