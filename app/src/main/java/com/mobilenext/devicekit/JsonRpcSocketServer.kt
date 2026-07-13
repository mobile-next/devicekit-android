package com.mobilenext.devicekit

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

// JsonRpcSocketServer serves JSON-RPC 2.0 over a localabstract socket, one
// request per connection framed as HTTP/1.1 (Content-Length + body). The host
// reaches it with `adb forward tcp:N localabstract:<name>` and a plain POST.
//
// It is shared by the two devicekit control surfaces that run as different
// processes and uids: DeviceKitServer (Instrumentation, app uid, uiAutomation)
// and AvcServer's encoder control socket (app_process, shell uid, MediaCodec).
// Both need the same framing; only the dispatched methods differ.
class JsonRpcSocketServer(
    private val socketName: String,
    private val dispatch: (method: String, params: JSONObject?) -> Any,
) {
    companion object {
        private const val TAG = "JsonRpcSocketServer"
        private const val JSONRPC_VERSION = "2.0"

        // JSON-RPC 2.0 standard error codes
        const val ERROR_PARSE = -32700
        const val ERROR_METHOD_NOT_FOUND = -32601
        const val ERROR_INTERNAL = -32603

        private const val CONTENT_LENGTH_PREFIX = "content-length:"
    }

    // A dispatch handler throws RpcException to return a specific JSON-RPC error
    // instead of the generic internal error.
    class RpcException(val code: Int, override val message: String) : Exception(message)

    @Volatile
    private var running = true
    private var serverSocket: LocalServerSocket? = null

    // start binds the socket and serves connections on the calling thread until
    // stop() is called or the socket errors. Blocking.
    fun start() {
        val socket = LocalServerSocket(socketName)
        serverSocket = socket
        Log.i(TAG, "Listening on localabstract:$socketName")
        while (running) {
            val conn = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed on $socketName", e)
                break
            }
            try {
                handleConnection(conn)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling connection", e)
            } finally {
                try {
                    conn.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    // startDaemon runs start() on a daemon thread and returns immediately.
    fun startDaemon(): Thread {
        val thread = Thread {
            try {
                start()
            } catch (e: Exception) {
                Log.e(TAG, "server on $socketName stopped: ${e.message}")
            }
        }
        thread.isDaemon = true
        thread.name = "jsonrpc-$socketName"
        thread.start()
        return thread
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private fun handleConnection(conn: LocalSocket) {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.ISO_8859_1))

        reader.readLine() ?: return // request line

        var contentLength = 0
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            if (line.lowercase().startsWith(CONTENT_LENGTH_PREFIX)) {
                contentLength = line.substring(CONTENT_LENGTH_PREFIX.length).trim().toIntOrNull() ?: 0
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

        val responseJson = handleJsonRpc(body)
        val responseBytes = responseJson.toByteArray(StandardCharsets.UTF_8)

        val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
        conn.outputStream.write(httpResponse.toByteArray(StandardCharsets.UTF_8))
        conn.outputStream.write(responseBytes)
        conn.outputStream.flush()
    }

    private fun handleJsonRpc(body: String): String {
        val request: JSONObject
        try {
            request = JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            return jsonRpcError(null, ERROR_PARSE, "Parse error: ${e.message}")
        }

        val id = request.opt("id")
        return try {
            val method = request.optString("method")
            val params = request.optJSONObject("params")
            jsonRpcResult(id, dispatch(method, params))
        } catch (e: RpcException) {
            jsonRpcError(id, e.code, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Internal error", e)
            jsonRpcError(id, ERROR_INTERNAL, "Internal error: ${e.message}")
        }
    }

    private fun jsonRpcResult(id: Any?, result: Any): String =
        JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun jsonRpcError(id: Any?, code: Int, message: String?): String =
        JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message ?: ""))
            .toString()
}
