@file:JvmName("UiDump")

package com.mobilenext.devicekit

import android.os.Looper
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Standalone entry point that dumps the current UI hierarchy as JSON to stdout,
 * without an Instrumentation host. The headless counterpart of [ViewTreeDump].
 *
 * Usage:
 *   adb shell CLASSPATH=/path/to/classes.dex app_process / \
 *     com.mobilenext.devicekit.UiDump [waitUntilIdleMs]
 *
 * Must be run as the shell or root user (see [UiAutomationFactory]).
 */
fun main(args: Array<String>) {
    val waitUntilIdle = args.getOrNull(0)?.toLongOrNull() ?: 0L

    // A bare app_process has no main looper (a normal app gets one from
    // ActivityThread). The accessibility framework builds a Handler on the main
    // looper, so prepare one here and run the dump on a worker thread while the
    // main thread services looper callbacks.
    Looper.prepareMainLooper()

    thread(name = "devicekit-uidump") {
        try {
            val uiAutomation = UiAutomationFactory.createAndConnect()
            val json = UiTreeSerializer.dump(uiAutomation, waitUntilIdle)
            println(json)
            System.out.flush()
            exitProcess(0)
        } catch (t: Throwable) {
            System.err.println("Error: ${t.message}")
            t.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    Looper.loop()
}
