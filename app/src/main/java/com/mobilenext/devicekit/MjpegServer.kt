package com.mobilenext.devicekit

import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

class MjpegServer(private val quality: Int, private val scale: Float) {
    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "BoundaryString"
        private const val DEFAULT_QUALITY = 80
        private const val DEFAULT_SCALE = 1.0f

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val (quality, scale) = parseArguments(args)
                val server = MjpegServer(quality, scale)
                server.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MJPEG stream", e)
                System.err.println("Error: ${e.message}")
                exitProcess(1)
            }
        }

        private fun parseArguments(args: Array<String>): Pair<Int, Float> {
            var quality = DEFAULT_QUALITY
            var scale = DEFAULT_SCALE

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--quality" -> {
                        if (i + 1 < args.size) {
                            quality = args[i + 1].toIntOrNull()?.coerceIn(1, 100) ?: DEFAULT_QUALITY
                            i++
                        }
                    }
                    "--scale" -> {
                        if (i + 1 < args.size) {
                            scale = args[i + 1].toFloatOrNull()?.coerceIn(0.1f, 2.0f) ?: DEFAULT_SCALE
                            i++
                        }
                    }
                }
                i++
            }

            return Pair(quality, scale)
        }
    }

    private val shutdownLatch = CountDownLatch(1)

    private fun start() {
        try {
            // Register shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(Thread {
                Log.d(TAG, "Shutdown hook triggered")
                shutdown()
            })

            // Output initial HTTP headers for MJPEG stream
            // gilm: outputMjpegHeaders()

            // Start continuous streaming
            streamFrames()

        } catch (e: Exception) {
            Log.e(TAG, "Error in MJPEG stream", e)
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
    }

    private fun outputMjpegHeaders() {
        val headers = """Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY
Cache-Control: no-cache
Connection: close

"""
        System.out.print(headers)
        System.out.flush()
    }

    private fun streamFrames() {
        val displayInfo = DisplayUtils.getDisplayInfo()
        val scaledWidth = (displayInfo.width * scale).toInt()
        val scaledHeight = (displayInfo.height * scale).toInt()
        Log.d(TAG, "Display info: ${displayInfo.width}x${displayInfo.height}, scaled: ${scaledWidth}x${scaledHeight}, quality: $quality")

        // Create a background thread with looper for image callbacks
        val handlerThread = HandlerThread("ScreenCapture")
        handlerThread.start()
        val backgroundHandler = Handler(handlerThread.looper)

        // Create ImageReader for capturing raw pixels
        val imageReader = ImageReader.newInstance(
            displayInfo.width,
            displayInfo.height,
            PixelFormat.RGBA_8888,
            1
        )

        // Set up image capture callback with background handler
        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val jpegData = ImageUtils.convertToJpeg(image, quality, scale)
                    outputMjpegFrame(jpegData)
                    Log.d(TAG, "Frame output: ${jpegData.size} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                image?.close()
            }
        }, backgroundHandler)

        // Create virtual display to capture screen
        val virtualDisplay = DisplayUtils.createVirtualDisplay(
            "mjpeg.screen.capture",
            displayInfo.width,
            displayInfo.height,
            displayInfo.dpi,
            imageReader.surface
        )

        if (virtualDisplay == null) {
            System.err.println("Error: Failed to create virtual display")
            exitProcess(1)
        }

        // Keep streaming until shutdown is requested
        try {
            shutdownLatch.await()
        } finally {
            virtualDisplay.release()
            imageReader.close()
            handlerThread.quitSafely()
        }
    }

    private fun shutdown() {
        shutdownLatch.countDown()
    }

    private fun outputMjpegFrame(jpegData: ByteArray) {
        val frameHeaders = "--$BOUNDARY\r\n" +
                "Content-type: image/jpeg\r\n" +
                "Content-Length: ${jpegData.size}\r\n" +
                "\r\n"
        System.out.print(frameHeaders)
        System.out.write(jpegData)
        System.out.print("\r\n")
        System.out.flush()
    }
}

