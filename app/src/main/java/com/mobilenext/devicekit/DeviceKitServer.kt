package com.mobilenext.devicekit

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class DeviceKitServer : Instrumentation() {

    companion object {
        private const val TAG = "DeviceKitServer"
        private const val SOCKET_NAME = "devicekit"

        // JSON-RPC 2.0 protocol (https://www.jsonrpc.org/specification)
        private const val JSONRPC_VERSION = "2.0"

        // JSON-RPC 2.0 standard error codes
        private const val JSONRPC_PARSE_ERROR = -32700
        private const val JSONRPC_METHOD_NOT_FOUND = -32601
        private const val JSONRPC_INTERNAL_ERROR = -32603
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread { runServer() }.start()
    }

    private fun runServer() {
        val uiAutomation = uiAutomation
        var serverSocket: LocalServerSocket? = null
        try {
            UiAutomationFactory.configureForWindowRetrieval(uiAutomation)

            serverSocket = LocalServerSocket(SOCKET_NAME)
            Log.i(TAG, "Listening on localabstract:$SOCKET_NAME")

            while (true) {
                val conn = serverSocket.accept()
                try {
                    handleConnection(conn, uiAutomation)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling connection", e)
                } finally {
                    conn.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
            val error = Bundle()
            error.putString("error", e.message ?: e.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, error)
        } finally {
            serverSocket?.close()
        }
    }

    private fun handleConnection(conn: LocalSocket, uiAutomation: UiAutomation) {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.ISO_8859_1))

        reader.readLine() ?: return

        var contentLength = 0
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substring(15).trim().toIntOrNull() ?: 0
            }
            line = reader.readLine()
        }

        val bodyChars = CharArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val n = reader.read(bodyChars, offset, contentLength - offset)
            if (n == -1) break
            offset += n
        }
        val body = String(bodyChars, 0, offset)

        val responseJson = handleJsonRpc(body, uiAutomation)
        val responseBytes = responseJson.toByteArray(Charsets.UTF_8)

        val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
        conn.outputStream.write(httpResponse.toByteArray(Charsets.UTF_8))
        conn.outputStream.write(responseBytes)
        conn.outputStream.flush()
    }

    private fun handleJsonRpc(body: String, uiAutomation: UiAutomation): String {
        val request: JSONObject
        try {
            request = JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            return jsonRpcError(null, JSONRPC_PARSE_ERROR, "Parse error: ${e.message}")
        }

        val id = request.opt("id")
        return try {
            val method = request.optString("method")
            val params = request.optJSONObject("params")

            when (method) {
                "device.dump.ui" -> {
                    val waitUntilIdle = params?.optLong("waitUntilIdle") ?: 0L
                    val hierarchy = JSONObject(UiTreeSerializer.dump(uiAutomation, waitUntilIdle))
                    jsonRpcResult(id, hierarchy)
                }
                "device.io.keyboard.hide" -> {
                    val dismissed = hideKeyboard(uiAutomation)
                    jsonRpcResult(id, JSONObject().put("dismissed", dismissed))
                }
                else -> jsonRpcError(id, JSONRPC_METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Internal error", e)
            jsonRpcError(id, JSONRPC_INTERNAL_ERROR, "Internal error: ${e.message}")
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

    private fun jsonRpcResult(id: Any?, result: Any): String =
        JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun jsonRpcError(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
}
