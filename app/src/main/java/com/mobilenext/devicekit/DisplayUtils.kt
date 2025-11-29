package com.mobilenext.devicekit

import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Shared utilities for display information and virtual display creation.
 * Used by both MjpegServer and AvcServer.
 */
object DisplayUtils {
    private const val TAG = "DisplayUtils"

    data class DisplayInfo(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val rotation: Int
    )

    /**
     * Get display information for the primary display using reflection.
     * Falls back to reasonable defaults if reflection fails.
     */
    fun getDisplayInfo(): DisplayInfo {
        return try {
            // Get display manager service
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)

            // Get display service
            val displayService = getServiceMethod.invoke(null, "display") as IBinder
            val displayManagerClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterfaceMethod = displayManagerClass.getMethod("asInterface", IBinder::class.java)
            val displayManager = asInterfaceMethod.invoke(null, displayService)

            // Get display info
            val getDisplayInfoMethod = displayManager.javaClass.getMethod("getDisplayInfo", Int::class.java)
            val displayInfo = getDisplayInfoMethod.invoke(displayManager, Display.DEFAULT_DISPLAY)
                ?: throw IllegalStateException("DisplayInfo is null")

            // Extract width, height, DPI, and rotation from DisplayInfo
            val logicalWidthField = displayInfo.javaClass.getField("logicalWidth")
            val logicalHeightField = displayInfo.javaClass.getField("logicalHeight")
            val logicalDensityDpiField = displayInfo.javaClass.getField("logicalDensityDpi")
            val rotationField = displayInfo.javaClass.getField("rotation")

            val width = logicalWidthField.getInt(displayInfo)
            val height = logicalHeightField.getInt(displayInfo)
            val dpi = logicalDensityDpiField.getInt(displayInfo)
            val rotation = rotationField.getInt(displayInfo)

            DisplayInfo(width, height, dpi, rotation)

        } catch (e: Exception) {
            Log.w(TAG, "Failed to get display info via DisplayManager, using fallback", e)
            DisplayInfo(1080, 1920, 320, Surface.ROTATION_0)
        }
    }

    /**
     * Create a virtual display using reflection.
     * This allows screen capture without needing MediaProjection API.
     *
     * @param name Display name for identification
     * @param width Display width in pixels
     * @param height Display height in pixels
     * @param dpi Display density
     * @param surface Output surface (from ImageReader or MediaCodec)
     * @return VirtualDisplay instance or null on failure
     */
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        return try {
            // Access DisplayManager through reflection
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val displayService = getServiceMethod.invoke(null, "display")

            val displayManagerStubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterfaceMethod = displayManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val displayManager = asInterfaceMethod.invoke(null, displayService)

            // Use the working createVirtualDisplay method
            val createVirtualDisplayMethod = android.hardware.display.DisplayManager::class.java
                .getMethod(
                    "createVirtualDisplay",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Surface::class.java
                )

            createVirtualDisplayMethod.invoke(
                null,
                name,
                width,
                height,
                dpi,
                surface
            ) as VirtualDisplay

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            null
        }
    }
}
