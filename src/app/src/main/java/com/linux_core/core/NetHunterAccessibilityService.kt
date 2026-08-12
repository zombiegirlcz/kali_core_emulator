package com.linux_core.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class NetHunterAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "NHAccessibilityService"

        @Volatile
        private var instance: NetHunterAccessibilityService? = null

        fun isServiceRunning(): Boolean {
            return instance != null
        }

        fun getScreenHierarchy(): String {
            val inst = instance ?: return "{\"error\":\"Accessibility service is not running or enabled\"}"
            val root = inst.rootInActiveWindow ?: return "{\"error\":\"No active window content root\"}"
            return try {
                val json = nodeToJSON(root)
                json.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error generating screen hierarchy: ${e.message}")
                "{\"error\":\"Failed to generate hierarchy: ${e.message}\"}"
            }
        }

        private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            return root.findAccessibilityNodeInfosByText(text).firstOrNull()
        }

        fun tap(x: Int, y: Int): Boolean {
            val inst = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            return inst.dispatchGesture(gesture, null, null)
        }

        fun longTap(x: Int, y: Int): Boolean {
            val inst = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
                .build()
            return inst.dispatchGesture(gesture, null, null)
        }

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            val inst = instance ?: return false
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            return inst.dispatchGesture(gesture, null, null)
        }

        fun clickByText(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val node = findNodeByText(root, text) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        fun longClickByText(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val node = findNodeByText(root, text) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }

        fun setText(text: String, targetText: String?): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val node = if (targetText != null) {
                findNodeByText(root, targetText) ?: return false
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun scroll(forward: Boolean, targetText: String?): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val node = if (targetText != null) findNodeByText(root, targetText) else root
            node ?: return false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                         else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return node.performAction(action)
        }

        fun globalAction(action: String): Boolean {
            val inst = instance ?: return false
            val a = when (action) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                else -> return false
            }
            return inst.performGlobalAction(a)
        }

        private fun nodeToJSON(node: AccessibilityNodeInfo): JSONObject {
            val json = JSONObject()
            json.put("className", node.className?.toString() ?: "")
            json.put("packageName", node.packageName?.toString() ?: "")
            if (node.text != null) {
                json.put("text", node.text.toString())
            }
            if (node.contentDescription != null) {
                json.put("contentDescription", node.contentDescription.toString())
            }
            if (node.viewIdResourceName != null) {
                json.put("viewId", node.viewIdResourceName)
            }
            json.put("clickable", node.isClickable)
            json.put("enabled", node.isEnabled)
            json.put("focused", node.isFocused)

            val rect = Rect()
            node.getBoundsInScreen(rect)
            json.put("bounds", JSONObject().apply {
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
            })

            if (node.childCount > 0) {
                val children = JSONArray()
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        children.put(nodeToJSON(child))
                    }
                }
                json.put("children", children)
            }
            return json
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service Created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only observe window events
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }
}
