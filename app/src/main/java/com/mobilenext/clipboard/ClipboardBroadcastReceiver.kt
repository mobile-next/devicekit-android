package com.mobilenext.clipboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

class ClipboardBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ClipboardReceiver"
        const val ACTION_SET_CLIPBOARD = "devicekit.clipboard.set"
        const val ACTION_CLEAR_CLIPBOARD = "devicekit.clipboard.clear"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENCODING = "encoding"
        const val ENCODING_BASE64 = "base64"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.w(TAG, "Received null context or intent")
            return
        }

        when (intent.action) {
            ACTION_SET_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text != null) {
                    var encoding = "text"
                    if (intent.hasExtra(EXTRA_ENCODING)) {
                        encoding = intent.getStringExtra(EXTRA_ENCODING)!!
                    }

                    setClipboardText(context, text, encoding)
                } else {
                    Log.w(TAG, "No text provided in broadcast intent")
                }
            }

            ACTION_CLEAR_CLIPBOARD -> {
                clearClipboardText(context);
            }

            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    private fun setClipboardText(context: Context, text: String, encoding: String) {
        try {
            var _text = text
            if (encoding == ENCODING_BASE64) {
                val decodedBytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                _text = String(decodedBytes, Charsets.UTF_8)
            }

            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("Clipboard Text", _text)
            clipboardManager.setPrimaryClip(clipData)
	    Log.i(TAG, "Clipboard set with text: $_text")
            Log.d(TAG, "Successfully set clipboard text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard text", e)
        }
    }

    private fun clearClipboardText(context: Context) {
        try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.clearPrimaryClip();
            Log.d(TAG, "Successfully cleared the clipboard text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear clipboard text", e)
        }
    }
} 