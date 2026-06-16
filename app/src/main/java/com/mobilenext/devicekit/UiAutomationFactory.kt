package com.mobilenext.devicekit

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Builds and connects a [UiAutomation] without an Instrumentation host, so UI
 * automation can run from a standalone app_process invocation:
 *
 *   adb shell CLASSPATH=/path/to/classes.dex app_process / com.mobilenext.devicekit.<Tool>
 *
 * Must run as the shell (uid 2000) or root user: UiAutomationConnection.connect()
 * registers with AccessibilityManagerService / WindowManager, which require those
 * permissions. Relies on @hide framework members reached via reflection (the
 * UiAutomation(Looper, IUiAutomationConnection) constructor and connect()), so the
 * exact shapes can shift between Android versions.
 */
object UiAutomationFactory {

    // How long to wait for the first accessibility event after configuring the
    // service, signalling the connection is live before the first query.
    private const val CONNECT_TIMEOUT_SECONDS = 2L

    /**
     * Constructs a fresh [UiAutomation] on its own looper thread, connects it to
     * the system, and configures it to retrieve interactive windows. Intended for
     * standalone app_process entry points.
     */
    fun createAndConnect(): UiAutomation {
        val thread = HandlerThread("devicekit-uiautomation").apply { start() }
        val uiAutomation = construct(thread.looper)
        connect(uiAutomation)
        configureForWindowRetrieval(uiAutomation)
        return uiAutomation
    }

    /**
     * Subscribes to all accessibility events and requests the flags needed to walk
     * the full window hierarchy. Shared by the standalone bootstrap and the
     * Instrumentation-hosted entry points, which already receive a connected
     * [UiAutomation] from the framework.
     */
    fun configureForWindowRetrieval(uiAutomation: UiAutomation) {
        val latch = CountDownLatch(1)
        uiAutomation.setOnAccessibilityEventListener { latch.countDown() }
        uiAutomation.serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        uiAutomation.setOnAccessibilityEventListener(null)
    }

    private fun construct(looper: Looper): UiAutomation {
        // android.app.UiAutomationConnection is @hide but has a public no-arg ctor.
        val connection = Class.forName("android.app.UiAutomationConnection")
            .getDeclaredConstructor()
            .newInstance()

        // The @hide UiAutomation(Looper, IUiAutomationConnection) constructor.
        val connectionInterface = Class.forName("android.app.IUiAutomationConnection")
        val constructor = UiAutomation::class.java
            .getDeclaredConstructor(Looper::class.java, connectionInterface)
            .apply { isAccessible = true }

        return constructor.newInstance(looper, connection) as UiAutomation
    }

    private fun connect(uiAutomation: UiAutomation) {
        // UiAutomation.connect() is @hide, so it is not visible at compile time.
        UiAutomation::class.java.getMethod("connect").invoke(uiAutomation)
    }
}
