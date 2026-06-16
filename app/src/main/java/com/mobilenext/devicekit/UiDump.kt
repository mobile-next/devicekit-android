@file:JvmName("UiDump")

package com.mobilenext.devicekit

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
    try {
        val uiAutomation = UiAutomationFactory.createAndConnect()
        val json = UiTreeSerializer.dump(uiAutomation, waitUntilIdle)
        println(json)
        exitProcess(0)
    } catch (t: Throwable) {
        System.err.println("Error: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(1)
    }
}
