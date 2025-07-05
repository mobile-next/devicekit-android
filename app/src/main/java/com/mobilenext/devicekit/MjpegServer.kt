package com.mobilenext.devicekit

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class MjpegServer {
    companion object {
        private const val TAG = "MjpegServer"
        
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val server = MjpegServer()
                server.captureScreenshot()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture screenshot", e)
                System.err.println("Error: ${e.message}")
                exitProcess(1)
            }
        }
    }

    private fun captureScreenshot() {
        try {
            val startTime = System.currentTimeMillis()
            
            // Get display information
            val displayInfo = getDisplayInfo()
            Log.d(TAG, "Display info: ${displayInfo.width}x${displayInfo.height}")
            
            // Create ImageReader for capturing raw pixels
            val imageReader = ImageReader.newInstance(
                displayInfo.width, 
                displayInfo.height, 
                PixelFormat.RGBA_8888, 
                1
            )
            
            val captureComplete = CountDownLatch(1)
            var jpegData: ByteArray? = null
            
            // Prepare the main thread looper if needed
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
            
            // Set up image capture callback with manual processing
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        jpegData = convertImageToJpeg(image)
                        image.close()
                        captureComplete.countDown()
                        Log.d(TAG, "Image captured and converted to JPEG: ${jpegData?.size} bytes")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    captureComplete.countDown()
                }
            }, Handler(Looper.myLooper()!!))
            
            // Create virtual display to capture screen
            val virtualDisplay = createVirtualDisplay(
                displayInfo.width,
                displayInfo.height, 
                displayInfo.dpi,
                imageReader.surface
            )
            
            // Wait for capture (max 5 seconds)
            val captured = captureComplete.await(5, TimeUnit.SECONDS)
            
            // Cleanup
            virtualDisplay?.release()
            imageReader.close()
            
            val duration = System.currentTimeMillis() - startTime
            
            if (captured && jpegData != null) {
                // Output JPEG data
                System.out.write(jpegData!!)
                System.out.flush()
                
                Log.d(TAG, "JPEG screenshot captured successfully: ${displayInfo.width}x${displayInfo.height} (took ${duration}ms)")
            } else {
                System.err.println("Error: Failed to capture screenshot - timeout or no data")
                exitProcess(1)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot", e)
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
    }
    
    private fun convertImageToJpeg(image: Image): ByteArray {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        // Create bitmap from image data
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Crop bitmap if there's padding
        val finalBitmap = if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
        
        // Convert to JPEG
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val jpegData = outputStream.toByteArray()
        
        // Cleanup
        if (finalBitmap != bitmap) {
            bitmap.recycle()
        }
        finalBitmap.recycle()
        outputStream.close()
        
        return jpegData
    }
    
    private fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface): VirtualDisplay? {
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
                .getMethod("createVirtualDisplay", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java)
            
            createVirtualDisplayMethod.invoke(null, "screenshot", width, height, 0, surface) as VirtualDisplay
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            null
        }
    }

    private fun getDisplayInfo(): DisplayInfo {
        try {
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
            
            // Extract width, height, and rotation from DisplayInfo
            val logicalWidthField = displayInfo.javaClass.getField("logicalWidth")
            val logicalHeightField = displayInfo.javaClass.getField("logicalHeight")
            val logicalDensityDpiField = displayInfo.javaClass.getField("logicalDensityDpi")
            val rotationField = displayInfo.javaClass.getField("rotation")
            
            val width = logicalWidthField.getInt(displayInfo)
            val height = logicalHeightField.getInt(displayInfo)
            val dpi = logicalDensityDpiField.getInt(displayInfo)
            val rotation = rotationField.getInt(displayInfo)
            
            return DisplayInfo(width, height, dpi, rotation)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get display info via DisplayManager, using fallback", e)
            return DisplayInfo(1080, 1920, 320, Surface.ROTATION_0)
        }
    }

    private data class DisplayInfo(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val rotation: Int
    )
}

