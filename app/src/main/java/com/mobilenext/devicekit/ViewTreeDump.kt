package com.mobilenext.devicekit

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
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
            uiAutomation.setOnAccessibilityEventListener(null)

            if (waitUntilIdle > 0) {
                UiDevice.getInstance(this).waitForIdle(waitUntilIdle)
            }

            val windows: List<AccessibilityWindowInfo> = uiAutomation.windows
            val roots: List<AccessibilityNodeInfo> = if (windows.isNotEmpty()) {
                windows.mapNotNull { it.root }
            } else {
                listOfNotNull(uiAutomation.rootInActiveWindow)
            }

            Log.i(TAG, "windows=${windows.size} roots=${roots.size}")
            val json = serializeToJson(roots)
            roots.forEach { it.recycle() }
            Log.i(TAG, "JSON size: ${json.length} chars")

            val result = Bundle()
            result.putString("json", json)
            sendStatus(0, result)
            finish(Activity.RESULT_OK, Bundle())
        }.start()
    }

    private fun serializeToJson(roots: List<AccessibilityNodeInfo>): String {
        val array = JSONArray()
        for (root in roots) {
            array.put(nodeToJson(root, 0))
        }
        return JSONObject().put("hierarchy", array).toString()
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, index: Int): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val obj = JSONObject()
            .put("index", index)
            .put("class", node.className?.toString() ?: "")
            .put("package", node.packageName?.toString() ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("content-desc", node.contentDescription?.toString() ?: "")
            .put("resource-id", node.viewIdResourceName ?: "")
            .put("checkable", node.isCheckable)
            .put("checked", node.isChecked)
            .put("clickable", node.isClickable)
            .put("enabled", node.isEnabled)
            .put("focusable", node.isFocusable)
            .put("focused", node.isFocused)
            .put("scrollable", node.isScrollable)
            .put("long-clickable", node.isLongClickable)
            .put("password", node.isPassword)
            .put("selected", node.isSelected)
            .put("visible", node.isVisibleToUser)
            .put("rect", JSONObject()
                .put("x", bounds.left).put("y", bounds.top)
                .put("width", bounds.right - bounds.left).put("height", bounds.bottom - bounds.top))

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            children.put(nodeToJson(child, i))
            child.recycle()
        }
        if (children.length() > 0) {
            obj.put("children", children)
        }

        return obj
    }
}
