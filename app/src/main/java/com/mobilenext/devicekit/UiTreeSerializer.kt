package com.mobilenext.devicekit

import android.app.UiAutomation
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeoutException

object UiTreeSerializer {

    private const val TAG = "UiTreeSerializer"

    // The UI must be quiet for this long before waitForIdle returns, bounded by
    // the caller-supplied global timeout. Mirrors UiDevice.waitForIdle semantics.
    private const val IDLE_WINDOW_MS = 500L

    fun dump(uiAutomation: UiAutomation, waitUntilIdle: Long = 0L): String {
        if (waitUntilIdle > 0) {
            // Best-effort settle: UiAutomation.waitForIdle throws TimeoutException
            // when the UI never goes idle (animations, video, spinners). Swallow it
            // and dump the current state, matching the old UiDevice.waitForIdle.
            try {
                uiAutomation.waitForIdle(IDLE_WINDOW_MS, waitUntilIdle)
            } catch (e: TimeoutException) {
                Log.w(TAG, "UI not idle within ${waitUntilIdle}ms; dumping current state", e)
            }
        }

        val windows: List<AccessibilityWindowInfo> = uiAutomation.windows
        val windowRoots = windows.mapNotNull { it.root }
        windows.forEach { it.recycle() }

        // Windows can be present yet expose null roots (not queryable at the
        // moment of the dump), which would otherwise yield an empty hierarchy.
        // Fall back to the active window's root in that case.
        val roots = if (windowRoots.isNotEmpty()) {
            windowRoots
        } else {
            listOfNotNull(uiAutomation.rootInActiveWindow)
        }

        try {
            return serialize(roots)
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    private fun serialize(roots: List<AccessibilityNodeInfo>): String {
        val array = JSONArray()
        for (root in roots) {
            array.put(nodeToJson(root, 0))
        }
        return JSONObject().put("hierarchy", array).toString()
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, index: Int): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // When a field is empty, AccessibilityNodeInfo.text returns the hint.
        // Separate the two: report empty text for an empty field and expose the
        // hint as its own attribute (mapped to `placeholder` downstream).
        val rawText = node.text?.toString() ?: ""
        val hintText = node.hintText?.toString() ?: ""
        val showingHint = node.isShowingHintText
        val text = if (showingHint) "" else rawText
        val hint = if (hintText.isNotEmpty()) hintText else if (showingHint) rawText else ""

        val obj = JSONObject()
            .put("index", index)
            .put("class", node.className?.toString() ?: "")
            .put("package", node.packageName?.toString() ?: "")
            .put("text", text)
            .put("hint", hint)
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
            try {
                children.put(nodeToJson(child, i))
            } finally {
                child.recycle()
            }
        }
        if (children.length() > 0) {
            obj.put("children", children)
        }

        return obj
    }
}
