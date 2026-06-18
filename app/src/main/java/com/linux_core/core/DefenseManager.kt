package com.linux_core.core

import android.util.Log
import java.io.File

object DefenseManager {
    private const val TAG = "DefenseManager"
    
    /**
     * Spustí ofenzivní protiopatření proti zadané IP adrese.
     */
    fun dispatch(targetIp: String, targetPort: Int, protocol: String) {
        Log.w(TAG, "🚀 DEFENSE TRIGGERED: Target=$targetIp, Port=$targetPort, Proto=$protocol")
        
        // Sestavení MSF příkazu pro automatický průzkum útočníka
        // -r: spustí v nethunteru (root)
        // -x: provede příkazy a ukončí se
        val msfCommands = """
            use auxiliary/scanner/portscan/tcp;
            set RHOSTS $targetIp;
            set PORTS $targetPort;
            run;
            exit;
        """.trimIndent().replace("\n", " ")

        val command = "nh -r msfconsole -q -x \"$msfCommands\""
        
        executeShell(command)
    }

    private fun executeShell(command: String) {
        Thread {
            try {
                Log.d(TAG, "Executing: $command")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    Log.i(TAG, "MSF Counter-attack initiated successfully:\n$output")
                } else {
                    Log.e(TAG, "MSF Counter-attack failed (code $exitCode):\n$error")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell execution error: ${e.message}")
            }
        }.start()
    }
}
