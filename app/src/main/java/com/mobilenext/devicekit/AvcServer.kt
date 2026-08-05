package com.mobilenext.devicekit

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

class AvcServer(private val bitrate: Int, private val scale: Float, private val fps: Int) {
    companion object {
        private const val TAG = "AvcServer"
        private const val DEFAULT_BITRATE = 3_000_000  // 3 Mbps — sane ceiling for screen mirroring over constrained links
        private const val MIN_BITRATE = 100_000        // 100 kbps floor for adaptive control
        private const val MAX_BITRATE = 10_000_000      // 10 Mbps ceiling for adaptive control
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_FPS = 30
        private const val MIN_FPS = 1
        private const val MAX_FPS = 60
        private const val I_FRAME_INTERVAL = 2  // 2s — 3s created larger bursts than 1s; tuning down

        // localabstract socket name for the live encoder control channel; the host
        // forwards to it (adb forward tcp:N localabstract:devicekit-avc).
        private const val CONTROL_SOCKET = "devicekit-avc"

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

        private fun parseArguments(args: Array<String>): Triple<Int, Float, Int> {
            var bitrate = DEFAULT_BITRATE
            var scale = DEFAULT_SCALE
            var fps = DEFAULT_FPS

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--bitrate" -> {
                        if (i + 1 < args.size) {
                            // Clamp startup bitrate to the same bounds as live updates
                            // so --bitrate can't bypass the MAX_BITRATE ceiling.
                            bitrate = args[i + 1].toIntOrNull()?.coerceIn(MIN_BITRATE, MAX_BITRATE) ?: DEFAULT_BITRATE
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
                            fps = if (parsedFps != null && parsedFps in MIN_FPS..MAX_FPS) {
                                parsedFps
                            } else {
                                Log.w(TAG, "Invalid fps value: ${args[i + 1]}. Using default: $DEFAULT_FPS")
                                DEFAULT_FPS
                            }
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

    // MediaCodec is driven from the encoder thread; control-socket commands are
    // enqueued here and drained there so all codec access stays single-threaded.
    private val codecCommands = ConcurrentLinkedQueue<() -> Unit>()

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

    private fun shutdown() {
        shutdownLatch.countDown()
    }

    private fun cleanupResources(
        stdoutChannel: java.nio.channels.FileChannel?,
        codec: MediaCodec?,
        virtualDisplay: VirtualDisplay?
    ) {
        try {
            stdoutChannel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing stdout channel", e)
        }
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec", e)
        }
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
    }

    // startControlServer exposes live encoder control over a localabstract JSON-RPC
    // socket. The host reaches it with `adb forward tcp:N localabstract:<CONTROL_SOCKET>`
    // and POSTs "screencapture.setBitrate" / "screencapture.requestKeyFrame". Stdin
    // can't be used: AvcServer is launched via `adb exec-out`, whose stdin is not
    // forwarded to the device process (exec-out is output-only) — a socket is the
    // only channel that reaches this shell-uid process. Control is best-effort: if
    // the socket can't bind, streaming continues unaffected.
    private fun startControlServer(codec: MediaCodec) {
        JsonRpcSocketServer(CONTROL_SOCKET) { method, params ->
            when (method) {
                "screencapture.setBitrate" -> {
                    val bps = params?.optInt("bps", -1) ?: -1
                    if (bps <= 0) {
                        throw JsonRpcSocketServer.RpcException(
                            JsonRpcSocketServer.ERROR_INTERNAL,
                            "setBitrate requires a positive 'bps'",
                        )
                    }
                    val clamped = bps.coerceIn(MIN_BITRATE, MAX_BITRATE)
                    // Apply on the encoder thread — never touch the codec from here.
                    codecCommands.add {
                        codec.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
                        })
                        Log.d(TAG, "Applied live bitrate: $clamped bps")
                    }
                    JSONObject().put("bitrate", clamped)
                }
                "screencapture.requestKeyFrame" -> {
                    codecCommands.add {
                        codec.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                        Log.d(TAG, "Requested immediate sync frame")
                    }
                    JSONObject().put("ok", true)
                }
                else -> throw JsonRpcSocketServer.RpcException(
                    JsonRpcSocketServer.ERROR_METHOD_NOT_FOUND,
                    "Method not found: $method",
                )
            }
        }.startDaemon { e ->
            // Bind runs on the daemon thread, so its failure surfaces here.
            Log.w(TAG, "Failed to start control server; live control unavailable", e)
        }
    }

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
            // CBR caps the encoder's peak rate so a burst of motion can't flood a
            // constrained viewer link (VBR was spiking to ~9 Mbps). A static screen
            // still idles low because surface input only feeds duplicate frames.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
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
            // Repeat previous frame after 100ms to keep stream alive when screen is static
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)  // 100ms in microseconds
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

        // Expose live encoder control (bitrate, keyframe) over a localabstract
        // socket so the host can adapt to the viewer's measured downlink without
        // restarting the stream.
        startControlServer(codec)

        val bufferInfo = MediaCodec.BufferInfo()
        val timeout = 100_000L  // 100ms timeout for responsive shutdown (matches REPEAT_FRAME_DELAY)

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

                // Apply pending control-socket commands on this (encoder) thread
                // so every MediaCodec.setParameters call is serialized with encoding.
                var command = codecCommands.poll()
                while (command != null) {
                    try {
                        command()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying codec command", e)
                    }
                    command = codecCommands.poll()
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
                                Log.d(TAG, "Output pipe broken, cleaning up and exiting")
                                cleanupResources(stdoutChannel, codec, virtualDisplay)
                                exitProcess(0)
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
            cleanupResources(stdoutChannel, codec, virtualDisplay)
        }
    }
}
