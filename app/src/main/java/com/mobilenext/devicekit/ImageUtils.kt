package com.mobilenext.devicekit

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream

object ImageUtils {
    
    @JvmStatic
    fun convertToJpeg(image: Image, quality: Int, scale: Float): ByteArray {
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

        // Remove row padding if present
        val paddingRemovedBitmap = if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
        
        // Scale bitmap if needed
        val finalBitmap = if (scale != 1.0f) {
            val scaledWidth = (image.width * scale).toInt()
            val scaledHeight = (image.height * scale).toInt()
            Bitmap.createScaledBitmap(paddingRemovedBitmap, scaledWidth, scaledHeight, true)
        } else {
            paddingRemovedBitmap
        }

        // Convert to JPEG
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val jpegData = outputStream.toByteArray()

        // Cleanup
        if (finalBitmap != bitmap) {
            bitmap.recycle()
        }
        if (paddingRemovedBitmap != bitmap && paddingRemovedBitmap != finalBitmap) {
            paddingRemovedBitmap.recycle()
        }
        if (finalBitmap != paddingRemovedBitmap) {
            finalBitmap.recycle()
        }
        outputStream.close()

        return jpegData
    }
}