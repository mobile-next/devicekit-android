package com.mobilenext.devicekit

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
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
}
