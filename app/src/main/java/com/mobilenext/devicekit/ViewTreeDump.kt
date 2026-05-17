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
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter
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
            val xml = serializeToXml(roots)
            roots.forEach { it.recycle() }
            Log.i(TAG, "XML size: ${xml.length} chars")

            val result = Bundle()
            result.putString("xml", xml)
            sendStatus(0, result)
            finish(Activity.RESULT_OK, Bundle())
        }.start()
    }

    private fun serializeToXml(roots: List<AccessibilityNodeInfo>): String {
        val writer = StringWriter()
        val serializer: XmlSerializer = XmlPullParserFactory.newInstance().newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        for (root in roots) {
            serializeNode(serializer, root, 0)
        }
        serializer.endTag("", "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }

    private fun serializeNode(serializer: XmlSerializer, node: AccessibilityNodeInfo, index: Int) {
        val className = node.className?.toString() ?: "unknown"
        val tag = className.substringAfterLast('.')

        serializer.startTag("", tag)
        serializer.attribute("", "index", index.toString())
        serializer.attribute("", "class", className)
        serializer.attribute("", "package", node.packageName?.toString() ?: "")
        serializer.attribute("", "text", node.text?.toString() ?: "")
        serializer.attribute("", "content-desc", node.contentDescription?.toString() ?: "")
        serializer.attribute("", "resource-id", node.viewIdResourceName ?: "")
        serializer.attribute("", "checkable", node.isCheckable.toString())
        serializer.attribute("", "checked", node.isChecked.toString())
        serializer.attribute("", "clickable", node.isClickable.toString())
        serializer.attribute("", "enabled", node.isEnabled.toString())
        serializer.attribute("", "focusable", node.isFocusable.toString())
        serializer.attribute("", "focused", node.isFocused.toString())
        serializer.attribute("", "scrollable", node.isScrollable.toString())
        serializer.attribute("", "long-clickable", node.isLongClickable.toString())
        serializer.attribute("", "password", node.isPassword.toString())
        serializer.attribute("", "selected", node.isSelected.toString())
        serializer.attribute("", "visible", node.isVisibleToUser.toString())

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        serializer.attribute("", "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            serializeNode(serializer, child, i)
            child.recycle()
        }

        serializer.endTag("", tag)
    }
}
