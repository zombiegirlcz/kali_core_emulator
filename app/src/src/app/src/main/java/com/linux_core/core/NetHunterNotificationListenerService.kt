package com.linux_core.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NetHunterNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "NHNotificationListener"
        
        @Volatile
        private var instance: NetHunterNotificationListenerService? = null

        fun getActiveNotificationsList(): List<NotificationData> {
            val inst = instance ?: return emptyList()
            return try {
                inst.activeNotifications.map { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras.getString("android.title") ?: ""
                    val text = extras.getCharSequence("android.text")?.toString() ?: ""
                    NotificationData(
                        packageName = sbn.packageName,
                        id = sbn.id,
                        title = title,
                        text = text,
                        postTime = sbn.postTime
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting active notifications: ${e.message}")
                emptyList()
            }
        }
    }

    data class NotificationData(
        val packageName: String,
        val id: Int,
        val title: String,
        val text: String,
        val postTime: Long
    )

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

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Listener Connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        Log.d(TAG, "Notification posted from: ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.d(TAG, "Notification removed from: ${sbn?.packageName}")
    }
}
