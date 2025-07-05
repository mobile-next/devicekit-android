package com.mobilenext.devicekit

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class SimpleDisplayCapture(
    private val displayId: Int = 0, // Main display by default
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30
) {
    
    companion object {
        private const val TAG = "SimpleDisplayCapture"
        private const val MIME_TYPE = "video/x-motion-jpeg" // Standard MJPEG MIME type
        private const val I_FRAME_INTERVAL = 2 // seconds
        private const val TIMEOUT_US = 10_000L // 10ms
        
        // Virtual display flags from DisplayManager
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    }
    
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var surface: Surface? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    
    private var createVirtualDisplayMethod: Method? = null
    
    private val isRunning = AtomicBoolean(false)
    private val isEncoding = AtomicBoolean(false)
    
    // Callback interface for receiving encoded data
    interface EncodedDataCallback {
        fun onEncodedData(data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(error: Exception)
    }
    
    private var dataCallback: EncodedDataCallback? = null
    
    fun setEncodedDataCallback(callback: EncodedDataCallback) {
        this.dataCallback = callback
    }
    
    @Throws(IOException::class)
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return
        }
        
        try {
            setupEncoder()
            setupDisplay()
            startEncoding()
            isRunning.set(true)
            Log.i(TAG, "Display capture started: ${width}x${height} @ ${frameRate}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            cleanup()
            throw e
        }
    }
    
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        Log.i(TAG, "Stopping display capture")
        isRunning.set(false)
        isEncoding.set(false)
        
        cleanup()
    }
    
    private fun setupEncoder() {
        try {
            // Create and configure MediaCodec
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                val format = createMediaFormat()
                Log.d(TAG, "Creating encoder with format: $format")
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = createInputSurface()
            }
            
            Log.d(TAG, "MediaCodec encoder configured successfully: $MIME_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encoder for $MIME_TYPE", e)
            // Fallback to H.264 if MJPEG doesn't work
            Log.w(TAG, "Falling back to H.264 encoder")
            mediaCodec = MediaCodec.createEncoderByType("video/avc").apply {
                val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = createInputSurface()
            }
            Log.d(TAG, "H.264 fallback encoder configured")
        }
    }
    
    private fun createMediaFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Every frame is a keyframe for MJPEG
            
            // MJPEG-specific settings
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            
            // Higher quality for single frame capture
            if (MIME_TYPE.contains("jpeg") || MIME_TYPE.contains("mjpeg")) {
                setInteger(MediaFormat.KEY_QUALITY, 90) // High quality JPEG
            }
        }
    }
    
    private fun setupDisplay() {
        val targetSurface = surface ?: throw IllegalStateException("Surface not created")
        
        // Use DisplayManager API for Android 16
        try {
            virtualDisplay = createVirtualDisplayWithDisplayManager(targetSurface)
            Log.d(TAG, "Using DisplayManager API")
        } catch (e: Exception) {
            Log.e(TAG, "DisplayManager failed", e)
            throw RuntimeException("Could not create display", e)
        }
    }
    
    private fun getCreateVirtualDisplayMethod(): Method {
        if (createVirtualDisplayMethod == null) {
            try {
                // Try the old API first
                createVirtualDisplayMethod = android.hardware.display.DisplayManager::class.java
                    .getMethod("createVirtualDisplay", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Surface::class.java)
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "Old createVirtualDisplay method not found, method may not be available")
                throw e
            }
        }
        return createVirtualDisplayMethod!!
    }
    
    private fun createVirtualDisplayWithDisplayManager(surface: Surface): VirtualDisplay {
        val method = getCreateVirtualDisplayMethod()
        return method.invoke(null, "scrcpy", width, height, displayId, surface) as VirtualDisplay
    }
    
    
    private fun startEncoding() {
        // Create encoder thread
        encoderThread = HandlerThread("EncoderThread").apply {
            start()
        }
        encoderHandler = Handler(encoderThread!!.looper)
        
        // Start MediaCodec
        mediaCodec?.start()
        isEncoding.set(true)
        
        // Start encoding loop on encoder thread
        encoderHandler?.post(encodingRunnable)
    }
    
    private val encodingRunnable = object : Runnable {
        override fun run() {
            if (!isEncoding.get()) {
                return
            }
            
            try {
                drainEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "Encoding error", e)
                dataCallback?.onError(e)
                return
            }
            
            // Continue encoding loop
            if (isEncoding.get()) {
                encoderHandler?.post(this)
            }
        }
    }
    
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val codec = mediaCodec ?: return
        
        while (isEncoding.get()) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet, continue loop
                    break
                }
                
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Encoder output format changed: ${codec.outputFormat}")
                    continue
                }
                
                outputBufferIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)
                    
                    if (encodedData != null && bufferInfo.size > 0) {
                        // Adjust buffer position and limit
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        
                        // Send encoded data to callback
                        dataCallback?.onEncodedData(encodedData, bufferInfo)
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    // Check for end of stream
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "End of stream reached")
                        break
                    }
                }
                
                else -> {
                    Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                }
            }
        }
    }
    
    private fun cleanup() {
        // Stop virtual display
        virtualDisplay?.release()
        virtualDisplay = null
        
        // Stop and release MediaCodec
        mediaCodec?.let { codec ->
            try {
                if (isEncoding.get()) {
                    codec.signalEndOfInputStream()
                }
                codec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping codec", e)
            } finally {
                codec.release()
            }
        }
        mediaCodec = null
        
        // Release surface
        surface?.release()
        surface = null
        
        // Stop encoder thread
        encoderThread?.quitSafely()
        try {
            encoderThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for encoder thread", e)
        }
        encoderThread = null
        encoderHandler = null
        
        Log.d(TAG, "Cleanup completed")
    }
    
    fun isRunning(): Boolean = isRunning.get()
    
    fun getVideoSize(): Pair<Int, Int> = Pair(width, height)
    
    fun getBitRate(): Int = bitRate
    
    fun getFrameRate(): Int = frameRate
}