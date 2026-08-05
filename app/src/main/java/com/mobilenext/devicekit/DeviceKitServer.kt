package com.mobilenext.devicekit

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

class DeviceKitServer : Instrumentation() {

    companion object {
        private const val TAG = "DeviceKitServer"
        private const val SOCKET_NAME = "devicekit"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread { runServer() }.start()
    }

    private fun runServer() {
        val uiAutomation = uiAutomation
        try {
            UiAutomationFactory.configureForWindowRetrieval(uiAutomation)

            JsonRpcSocketServer(SOCKET_NAME) { method, params ->
                when (method) {
                    "device.dump.ui" -> {
                        val waitUntilIdle = params?.optLong("waitUntilIdle") ?: 0L
                        JSONObject(UiTreeSerializer.dump(uiAutomation, waitUntilIdle))
                    }
                    "device.io.keyboard.hide" -> {
                        val dismissed = hideKeyboard(uiAutomation)
                        JSONObject().put("dismissed", dismissed)
                    }
                    else -> throw JsonRpcSocketServer.RpcException(
                        JsonRpcSocketServer.ERROR_METHOD_NOT_FOUND,
                        "Method not found: $method",
                    )
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
            val error = Bundle()
            error.putString("error", e.message ?: e.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, error)
        }
    }

    // The soft keyboard is just an IME-type window; if it's up, BACK dismisses it.
    // ponytail: no headless way to *show* the IME — Android only raises it for a
    // focused editable view in the target app, so only hide is offered here.
    private fun hideKeyboard(uiAutomation: UiAutomation): Boolean {
        val imeShown = uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!imeShown) return false

        val now = SystemClock.uptimeMillis()
        uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
        uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true)
        return true
    }
}
