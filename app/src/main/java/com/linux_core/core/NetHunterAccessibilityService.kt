package com.linux_core.core

import android.accessibilityservice.AccessibilityService
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
