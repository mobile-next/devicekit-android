package com.mobilenext.devicekit

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

class AvcServer(private val bitrate: Int, private val scale: Float, private val fps: Int) {
    companion object {
        private const val TAG = "AvcServer"
        private const val DEFAULT_BITRATE = 10_000_000  // 10 Mbps (Google's default)
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_FPS = 30
        private const val MIN_FPS = 1
        private const val MAX_FPS = 60
        private const val I_FRAME_INTERVAL = 1  /**
         * Program entry point that parses command-line arguments, creates an AvcServer, and starts streaming.
         *
         * On startup failure the function logs the error, prints the message to stderr, and exits the process with status 1.
         *
         * @param args Command-line arguments supporting `--bitrate <int>`, `--scale <float>`, and `--fps <int>`.
         */

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val (bitrate, scale, fps) = parseArguments(args)
                val server = AvcServer(bitrate, scale, fps)
                server.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AVC stream", e)
                System.err.println("Error: ${e.message}")
                exitProcess(1)
            }
        }

        /**
         * Parses command-line arguments for bitrate, scale, and fps and returns their validated values.
         *
         * Recognizes `--bitrate <value>`, `--scale <value>`, and `--fps <value>`. Missing or invalid
         * `--bitrate` and `--scale` values fall back to defaults; `--bitrate` is coerced to at least 100000
         * and `--scale` is coerced into the range 0.1f–2.0f. `--fps` must be an integer between MIN_FPS and
         * MAX_FPS.
         *
         * @param args The command-line arguments to parse.
         * @return A Triple of `(bitrate, scale, fps)` after validation and coercion.
         * @throws IllegalArgumentException if a provided `--fps` value is not an integer or is out of range.
         */
        private fun parseArguments(args: Array<String>): Triple<Int, Float, Int> {
            var bitrate = DEFAULT_BITRATE
            var scale = DEFAULT_SCALE
            var fps = DEFAULT_FPS

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--bitrate" -> {
                        if (i + 1 < args.size) {
                            bitrate = args[i + 1].toIntOrNull()?.coerceAtLeast(100_000) ?: DEFAULT_BITRATE
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
                            val parsedFps = args[i + 1].toIntOrNull()
                            if (parsedFps == null) {
                                throw IllegalArgumentException("Invalid fps value: ${args[i + 1]}. Must be an integer between $MIN_FPS and $MAX_FPS")
                            }
                            if (parsedFps < MIN_FPS || parsedFps > MAX_FPS) {
                                throw IllegalArgumentException("fps value out of range: $parsedFps. Must be between $MIN_FPS and $MAX_FPS")
                            }
                            fps = parsedFps
                            i++
                        }
                    }
                }
                i++
            }

            return Triple(bitrate, scale, fps)
        }
    }

    private val shutdownLatch = CountDownLatch(1)

    /**
     * Registers a JVM shutdown hook and starts the AVC streaming process.
     *
     * Initiates the encoder and stream lifecycle; if startup or streaming fails, logs the error,
     * writes the error message to standard error, and terminates the process with exit code 1.
     */
    private fun start() {
        try {
            // Register shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(Thread {
                Log.d(TAG, "Shutdown hook triggered")
                shutdown()
            })

            // Start H.264 streaming
            streamAvcFrames()

        } catch (e: Exception) {
            Log.e(TAG, "Error in AVC stream", e)
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }
    }

    /**
     * Signals the server to shut down by releasing the internal shutdown latch.
     *
     * Decrements the latch so threads waiting for shutdown can proceed and the encoding loop can exit.
     */
    private fun shutdown() {
        shutdownLatch.countDown()
    }

    /**
     * Sets up an H.264 (AVC) encoder for the current display and streams the encoded frames to standard output.
     *
     * The function obtains display dimensions, applies scaling, validates codec capabilities, configures
     * and starts a MediaCodec encoder with an input Surface, creates a virtual display that renders to
     * that surface, then enters a loop that writes encoded H.264 data directly to stdout until shutdown.
     *
     * @throws IllegalArgumentException if the scaled video dimensions are invalid (width or height <= 0)
     *         or if the scaled dimensions exceed the codec's supported size range. 
     */
    private fun streamAvcFrames() {
        val displayInfo = DisplayUtils.getDisplayInfo()
        val scaledWidth = (displayInfo.width * scale).toInt()
        val scaledHeight = (displayInfo.height * scale).toInt()

        Log.d(TAG, "Starting AVC stream: ${displayInfo.width}x${displayInfo.height} -> ${scaledWidth}x${scaledHeight}")
        Log.d(TAG, "Configuration: bitrate=$bitrate, fps=$fps, I-frame interval=${I_FRAME_INTERVAL}s")
        Log.d(TAG, "Scaled dimensions: width=$scaledWidth, height=$scaledHeight")

        // Validate dimensions
        if (scaledWidth <= 0 || scaledHeight <= 0) {
            throw IllegalArgumentException("Invalid dimensions: ${scaledWidth}x${scaledHeight}")
        }
        if (scaledWidth % 2 != 0 || scaledHeight % 2 != 0) {
            Log.w(TAG, "Warning: Dimensions not divisible by 2, may cause issues: ${scaledWidth}x${scaledHeight}")
        }

        // Check codec capabilities before attempting to configure
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val codecInfo = codec.codecInfo
        val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val videoCapabilities = capabilities.videoCapabilities

        Log.d(TAG, "Codec capabilities:")
        Log.d(TAG, "  Supported widths: ${videoCapabilities.supportedWidths}")
        Log.d(TAG, "  Supported heights: ${videoCapabilities.supportedHeights}")
        Log.d(TAG, "  Width alignment: ${videoCapabilities.widthAlignment}")
        Log.d(TAG, "  Height alignment: ${videoCapabilities.heightAlignment}")

        // Check if dimensions are supported
        if (!videoCapabilities.isSizeSupported(scaledWidth, scaledHeight)) {
            val maxWidth = videoCapabilities.supportedWidths.upper
            val maxHeight = videoCapabilities.supportedHeights.upper
            Log.e(TAG, "Dimensions ${scaledWidth}x${scaledHeight} not supported by codec")
            Log.e(TAG, "Maximum supported: ${maxWidth}x${maxHeight}")
            codec.release()
            throw IllegalArgumentException(
                "Video dimensions ${scaledWidth}x${scaledHeight} exceed codec capabilities. " +
                "Maximum supported: ${maxWidth}x${maxHeight}. " +
                "Try using --scale parameter to reduce resolution (e.g., --scale 0.5)"
            )
        }

        // Configure MediaCodec for H.264 encoding (Google's configuration)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            scaledWidth,
            scaledHeight
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)  // Set capture rate to match frame rate
            setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())  // Set operating rate
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Use High profile for better VUI support
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            // Low latency settings
            setInteger(MediaFormat.KEY_LATENCY, 0)  // Request lowest latency
            setInteger(MediaFormat.KEY_PRIORITY, 0)  // Realtime priority
        }

        Log.d(TAG, "MediaFormat created: $format")
        Log.d(TAG, "Codec created, attempting to configure...")

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d(TAG, "Codec configured successfully")

            // Log the actual output format to see what the codec set
            val outputFormat = codec.outputFormat
            Log.d(TAG, "Codec output format: $outputFormat")
            val actualFrameRate = outputFormat.getInteger(MediaFormat.KEY_FRAME_RATE, -1)
            Log.d(TAG, "Actual frame rate in output: $actualFrameRate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure codec with format: $format", e)
            codec.release()
            throw e
        }

        // Get input surface from codec
        val inputSurface = codec.createInputSurface()

        // Create virtual display to render to codec's input surface
        val virtualDisplay = DisplayUtils.createVirtualDisplay(
            "avc.screen.capture",
            scaledWidth,
            scaledHeight,
            displayInfo.dpi,
            inputSurface
        )

        if (virtualDisplay == null) {
            System.err.println("Error: Failed to create virtual display")
            codec.release()
            exitProcess(1)
        }

        // Start codec
        codec.start()
        Log.d(TAG, "AVC encoder started")

        val bufferInfo = MediaCodec.BufferInfo()
        val timeout = 10000L  // 10ms timeout for lower latency

        // Get FileChannel for stdout to write directly from ByteBuffer (zero-copy)
        val stdoutChannel = FileOutputStream(FileDescriptor.out).channel

        var frameCount = 0
        var lastPts = 0L
        var firstPts = 0L

        try {
            // Encoding loop - matches Google's libscreen-sharing-agent.so behavior
            while (!Thread.currentThread().isInterrupted) {
                // Check if shutdown requested
                if (shutdownLatch.count == 0L) {
                    break
                }

                // Dequeue encoded output buffer
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Write encoded H.264 data directly from ByteBuffer to stdout
                            // This is ZERO-COPY - ByteBuffer stays in native memory
                            // Blocking write provides backpressure (same as Google's SocketWriter)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // FileChannel.write() from DirectByteBuffer = zero-copy via DMA
                            try {
                                while (outputBuffer.hasRemaining()) {
                                    stdoutChannel.write(outputBuffer)
                                }
                            } catch (e: IOException) {
                                // Pipe broken - client disconnected
                                Log.d(TAG, "Output pipe broken, shutting down")
                                shutdown()
                                break
                            }

                            // Log frame info
                            val frameType = when {
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 -> "config"
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 -> "keyframe"
                                else -> "frame"
                            }

                            // Track presentation timestamps to calculate actual frame rate
                            if (frameType == "config") {
                                // Parse first few bytes to check for VUI
                                outputBuffer.position(bufferInfo.offset)
                                val firstBytes = ByteArray(minOf(20, bufferInfo.size))
                                outputBuffer.get(firstBytes)
                            } else {
                                if (frameCount == 0) {
                                    firstPts = bufferInfo.presentationTimeUs
                                }

                                if (frameCount > 0 && frameCount % 60 == 0) {
                                    val deltaPts = bufferInfo.presentationTimeUs - lastPts
                                    val totalTime = (bufferInfo.presentationTimeUs - firstPts) / 1_000_000.0
                                    val avgFps = frameCount / totalTime
                                    // Log.d(TAG, "Frame $frameCount: pts=${bufferInfo.presentationTimeUs}µs, delta=${deltaPts}µs, avg_fps=%.2f".format(avgFps))
                                }

                                lastPts = bufferInfo.presentationTimeUs
                                frameCount++
                            }

                            // Log.v(TAG, "AVC $frameType: ${bufferInfo.size} bytes")
                        }

                        // Release buffer back to codec (enables backpressure when slow)
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "Output format changed: $newFormat")
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No buffer available, continue (normal)
                    }
                    else -> {
                        Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                    }
                }
            }
        } finally {
            Log.d(TAG, "Stopping AVC encoder")
            stdoutChannel.close()
            codec.stop()
            codec.release()
            virtualDisplay.release()
        }
    }
}