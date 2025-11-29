package com.mobilenext.devicekit

import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

class MjpegServer(private val quality: Int, private val scale: Float, private val fps: Int) {
    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "BoundaryString"
        private const val DEFAULT_QUALITY = 80
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_FPS = 30

        /**
         * Program entry point that parses command-line options, creates an MjpegServer, and starts streaming.
         *
         * Exits the process with code 1 and logs an error if initialization fails.
         *
         * @param args Command-line arguments. Recognized options: `--quality=<0-100>`, `--scale=<positive float>`, `--fps=<1-60>`.
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val (quality, scale, fps) = parseArguments(args)
                val server = MjpegServer(quality, scale, fps)
                server.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MJPEG stream", e)
                System.err.println("Error: ${e.message}")
                exitProcess(1)
            }
        }

        /**
         * Parse command-line options for MJPEG quality, scale, and frames-per-second.
         *
         * Recognizes the `--quality`, `--scale`, and `--fps` options; each option expects a following value.
         * If an option is missing or its value is non-numeric or out of range, the corresponding default is used.
         *
         * @param args The command-line arguments to parse.
         * @return A `Triple` containing:
         *         - first: `quality` in the range 1..100,
         *         - second: `scale` in the range 0.1f..2.0f,
         *         - third: `fps` in the range 1..60.
         */
        private fun parseArguments(args: Array<String>): Triple<Int, Float, Int> {
            var quality = DEFAULT_QUALITY
            var scale = DEFAULT_SCALE
            var fps = DEFAULT_FPS

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
                    "--fps" -> {
                        if (i + 1 < args.size) {
                            fps = args[i + 1].toIntOrNull()?.coerceIn(1, 60) ?: DEFAULT_FPS
                            i++
                        }
                    }
                }
                i++
            }

            return Triple(quality, scale, fps)
        }
    }

    private val shutdownLatch = CountDownLatch(1)

    /**
     * Starts the MJPEG streaming lifecycle and registers a JVM shutdown hook for graceful termination.
     *
     * Registers a shutdown hook that triggers `shutdown()` when the JVM exits, then begins continuous
     * frame capture and streaming by invoking `streamFrames()`. If an unexpected exception occurs,
     * the error is logged to both logcat and stderr and the process exits with code 1.
     */
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

    /**
     * Writes the initial multipart MJPEG HTTP headers to standard output and flushes them.
     *
     * This emits the Content-Type (with boundary), Cache-Control, and Connection headers followed by a blank line to begin the multipart stream.
     */
    private fun outputMjpegHeaders() {
        val headers = """Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY
Cache-Control: no-cache
Connection: close

"""
        System.out.print(headers)
        System.out.flush()
    }

    /**
     * Captures the device screen, encodes frames as JPEG, and streams them as MJPEG multipart frames to stdout until shutdown.
     *
     * Starts a background thread and an ImageReader-backed virtual display to receive frames, processes each available image into a JPEG, writes framed output, and blocks until a shutdown is requested. Releases the virtual display, closes the ImageReader, and stops the background thread on exit.
     */
    private fun streamFrames() {
        val displayInfo = DisplayUtils.getDisplayInfo()
        val scaledWidth = (displayInfo.width * scale).toInt()
        val scaledHeight = (displayInfo.height * scale).toInt()
        Log.d(TAG, "Display info: ${displayInfo.width}x${displayInfo.height}, scaled: ${scaledWidth}x${scaledHeight}, quality: $quality, fps: $fps")

        // Create a background thread with looper for image callbacks
        val handlerThread = HandlerThread("ScreenCapture")
        handlerThread.start()
        val backgroundHandler = Handler(handlerThread.looper)

        // Create ImageReader for capturing raw pixels
        val imageReader = ImageReader.newInstance(
            displayInfo.width,
            displayInfo.height,
            PixelFormat.RGBA_8888,
            2
        )

        // Set up image capture callback with background handler
        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val jpegData = ImageUtils.convertToJpeg(image, quality, scale)
                    outputMjpegFrame(jpegData)
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

    /**
     * Signals the server to stop streaming and causes the streaming loop to exit.
     */
    private fun shutdown() {
        shutdownLatch.countDown()
    }

    /**
     * Writes a JPEG image as a single multipart MJPEG frame to standard output.
     *
     * Writes the multipart boundary and headers, the provided JPEG bytes, and a trailing CRLF, then flushes stdout. On I/O failure (for example a broken pipe from a disconnected client) the server shutdown is initiated.
     *
     * @param jpegData JPEG-encoded image bytes to emit as an MJPEG frame.
     */
    private fun outputMjpegFrame(jpegData: ByteArray) {
        try {
            val frameHeaders = "--$BOUNDARY\r\n" +
                    "Content-type: image/jpeg\r\n" +
                    "Content-Length: ${jpegData.size}\r\n" +
                    "\r\n"
            System.out.print(frameHeaders)
            System.out.write(jpegData)
            System.out.print("\r\n")
            System.out.flush()
        } catch (e: IOException) {
            // Pipe broken - client disconnected
            Log.d(TAG, "Output pipe broken, shutting down")
            shutdown()
        }
    }
}
