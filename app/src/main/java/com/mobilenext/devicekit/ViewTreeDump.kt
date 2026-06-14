package com.mobilenext.devicekit

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ViewTreeDump : Instrumentation() {

    companion object {
        const val TAG = "ViewTreeDump"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val waitUntilIdle = arguments?.getString("waitUntilIdle")?.toLongOrNull() ?: 0L
        Thread {
            val uiAutomation = uiAutomation
            try {
                val latch = CountDownLatch(1)
                uiAutomation.setOnAccessibilityEventListener { latch.countDown() }
                uiAutomation.serviceInfo = AccessibilityServiceInfo().apply {
                    eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                    feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                    flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                }
                latch.await(2, TimeUnit.SECONDS)

                val json = UiTreeSerializer.dump(uiAutomation, this, waitUntilIdle)
                Log.i(TAG, "JSON size: ${json.length} chars")

                val result = Bundle()
                result.putString("json", json)
                sendStatus(0, result)
                finish(Activity.RESULT_OK, Bundle())
            } catch (t: Throwable) {
                Log.e(TAG, "dump failed", t)
                val error = Bundle()
                error.putString("error", t.message ?: t.javaClass.simpleName)
                sendStatus(1, error)
                finish(Activity.RESULT_CANCELED, error)
            } finally {
                uiAutomation.setOnAccessibilityEventListener(null)
            }
        }.start()
    }
}
