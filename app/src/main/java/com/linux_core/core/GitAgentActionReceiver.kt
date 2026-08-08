package com.linux_core.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class GitAgentActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GitAgentActionReceiver"
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val repoId = intent.getStringExtra("repo_id") ?: return
        val action = intent.getStringExtra("action") ?: "none"
        val repoPath = intent.getStringExtra("repo_path") ?: ""
        val branch = intent.getStringExtra("branch") ?: "main"

        Log.i(TAG, "Received git-agent action: repo=$repoPath action=$action repoId=$repoId")

        // Cancel all related notifications
        GitAgentNotifier.cancel(context, repoId.hashCode() + 1)
        GitAgentNotifier.cancel(context, repoId.hashCode() + 2)
        GitAgentNotifier.cancel(context, repoId.hashCode() + 3)

        // Execute action in background
        executor.execute {
            try {
                executeGitAction(context, repoPath, branch, action, repoId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute git action: ${e.message}", e)
            }
        }
    }

    private fun executeGitAction(context: Context, repoPath: String, branch: String, action: String, repoId: String) {
        // Create action file in shared storage for PRoot git-agent to pick up
        val actionDir = File("/sdcard/.git-agent/actions")
        actionDir.mkdirs()

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val repoHash = repoPath.hashCode().toString().replace("-", "")
        val actionFile = File(actionDir, "${timestamp}-${repoHash}.json")

        val actionJson = """{"repo":"$repoPath","branch":"$branch","action":"$action","repo_id":"$repoId","ts":"$timestamp","status":"pending"}"""

        try {
            actionFile.writeText(actionJson)
            Log.i(TAG, "Action file written: ${actionFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write action file: ${e.message}")
        }

        // Try immediate execution via API callback
        try {
            val url = "http://127.0.0.1:1337/git-agent/action"
            val jsonPayload = """{"repo_id":"$repoId","action":"$action","repo_path":"$repoPath","branch":"$branch"}"""
            
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
            
            val responseCode = conn.responseCode
            Log.i(TAG, "API callback response: $responseCode")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "API callback failed (non-critical): ${e.message}")
        }

        // Show result notification
        val resultTitle = when (action) {
            "push" -> "Git Push ✅"
            "merge" -> "Git Merge ✅"
            "none" -> "Git Skipped"
            else -> "Git Action"
        }
        
        val resultContent = when (action) {
            "push" -> "Pushing $repoPath to $branch..."
            "merge" -> "Merging + pushing $repoPath..."
            "none" -> "Push skipped for $repoPath"
            else -> "Action: $action"
        }

        // Post result notification
        postResultNotification(context, resultTitle, resultContent)
    }

    private fun postResultNotification(context: Context, title: String, content: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = androidx.core.app.NotificationCompat.Builder(context, GitAgentNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setTimeoutAfter(5000)
                .build()
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post result notification: ${e.message}")
        }
    }
}
