@file:JvmName("Clipboard")

package com.mobilenext.devicekit

import android.util.Base64
import kotlin.system.exitProcess

/**
 * Standalone clipboard tool, the app_process replacement for the old
 * ClipboardBroadcastReceiver. Talks to the IClipboard binder service directly,
 * so it needs no installed app or running receiver.
 *
 * Usage:
 *   adb shell CLASSPATH=/path/to/classes.dex app_process / \
 *     com.mobilenext.devicekit.Clipboard <command>
 *
 *   set <text>            set the clipboard to the given text
 *   set --base64 <b64>    set the clipboard to UTF-8 decoded from base64
 *   get                   print the current clipboard text to stdout
 *   clear                 clear the clipboard
 *
 * Must be run as the shell or root user.
 */
fun main(args: Array<String>) {
    try {
        when (args.getOrNull(0)) {
            "set" -> ClipboardService.setText(parseSetText(args))
            "get" -> println(ClipboardService.getText() ?: "")
            "clear" -> ClipboardService.clear()
            else -> {
                System.err.println(
                    "Usage: Clipboard set <text> | set --base64 <b64> | get | clear",
                )
                exitProcess(2)
            }
        }
        exitProcess(0)
    } catch (t: Throwable) {
        System.err.println("Error: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun parseSetText(args: Array<String>): String {
    if (args.getOrNull(1) == "--base64") {
        val encoded = args.getOrNull(2) ?: error("missing base64 argument")
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }
    return args.getOrNull(1) ?: error("missing text argument")
}
