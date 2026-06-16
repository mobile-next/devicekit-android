package com.mobilenext.devicekit

import android.content.ClipData
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Talks to the system clipboard via the IClipboard binder service, so the
 * clipboard can be read/written from a standalone app_process invocation with no
 * Context or app runtime. This replaces the old ClipboardBroadcastReceiver.
 *
 * IClipboard's methods have gained parameters across Android versions
 * (attributionTag, userId, deviceId), always appended in a stable order. Rather
 * than hardcode a per-API signature, arguments are filled by parameter type and
 * position, which covers API 29 through current.
 */
object ClipboardService {

    // The clipboard service attributes the call to this caller; shell's package.
    private const val CALLING_PACKAGE = "com.android.shell"
    private const val USER_ID = 0
    private const val DEVICE_ID = 0 // Context.DEVICE_ID_DEFAULT

    private val clipboardInterface: Class<*> by lazy { Class.forName("android.content.IClipboard") }
    private val clipboard: Any by lazy { connect() }

    fun setText(text: String) {
        val clip = ClipData.newPlainText("devicekit", text)
        val method = findMethod("setPrimaryClip", ClipData::class.java)
        method.invoke(clipboard, *buildArgs(method, clip))
    }

    fun getText(): String? {
        val method = findMethod("getPrimaryClip")
        val clip = method.invoke(clipboard, *buildArgs(method, clip = null)) as ClipData?
        return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    fun clear() {
        val method = findMethod("clearPrimaryClip")
        method.invoke(clipboard, *buildArgs(method, clip = null))
    }

    private fun connect(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "clipboard") as IBinder?
            ?: error("clipboard service not available")
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder) ?: error("could not bind to IClipboard")
    }

    /** First overload of [name] whose leading parameters match [leading]. */
    private fun findMethod(name: String, vararg leading: Class<*>): Method =
        clipboardInterface.methods
            .filter { it.name == name }
            .firstOrNull { method ->
                leading.indices.all { i -> method.parameterTypes.getOrNull(i) == leading[i] }
            }
            ?: error("IClipboard has no matching $name method")

    private fun buildArgs(method: Method, clip: ClipData?): Array<Any?> {
        var stringSeen = 0
        var intSeen = 0
        return method.parameterTypes.map { type ->
            when (type) {
                ClipData::class.java -> clip
                String::class.java -> {
                    stringSeen++
                    if (stringSeen == 1) CALLING_PACKAGE else null // callingPackage, then attributionTag
                }
                Int::class.javaPrimitiveType -> {
                    intSeen++
                    if (intSeen == 1) USER_ID else DEVICE_ID // userId, then deviceId
                }
                else -> null
            }
        }.toTypedArray()
    }
}
