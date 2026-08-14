package com.linux_core.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.termux.terminal.TerminalSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ProcessResolver {
    private const val TAG = "ProcessResolver"

    data class ProcessInfo(
        val appName: String,
        val sessionName: String?,
        val packageName: String? = null
    )

    // Cache to avoid aggressive proc parsing
    // Key format: "protocol:localPort"
    private val cache = ConcurrentHashMap<String, CachedInfo>()
    private const val CACHE_TTL_MS = 30000 // 30 seconds

    private class CachedInfo(
        val info: ProcessInfo,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun resolve(context: Context, protocol: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): ProcessInfo {
        val localPort = if (srcIp == "172.18.11.218") srcPort else dstPort
        val cacheKey = "$protocol:$localPort"
        val cached = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.info
        }

        val resolved = performResolve(context, protocol, srcIp, srcPort, dstIp, dstPort)
        cache[cacheKey] = CachedInfo(resolved)
        return resolved
    }

    private fun performResolve(context: Context, protocol: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): ProcessInfo {
        val localPort = if (srcIp == "172.18.11.218") srcPort else dstPort
        val isTcp = protocol.equals("TCP", ignoreCase = true)
        val files = if (isTcp) {
            listOf("/proc/net/tcp", "/proc/net/tcp6", "/proc/self/net/tcp", "/proc/self/net/tcp6")
        } else {
            listOf("/proc/net/udp", "/proc/net/udp6", "/proc/self/net/udp", "/proc/self/net/udp6")
        }

        val portHex = String.format("%04X", localPort)
        var resolvedUid = -1
        var resolvedInode = -1L

        for (filePath in files) {
            try {
                val file = File(filePath)
                if (!file.exists()) continue
                val lines = file.readLines()
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 10) {
                        val localAddress = parts[1]
                        val localPortHex = localAddress.substringAfterLast(":")
                        if (localPortHex.equals(portHex, ignoreCase = true)) {
                            val candidateUid = parts[7].toIntOrNull() ?: -1
                            val candidateInode = parts[9].toLongOrNull() ?: -1L
                            if (filePath.contains("/proc/self/")) {
                                if (resolvedUid == -1) {
                                    resolvedUid = candidateUid
                                    resolvedInode = candidateInode
                                }
                            } else {
                                resolvedUid = candidateUid
                                resolvedInode = candidateInode
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            if (resolvedUid != -1 && !files[files.indexOf(filePath)].contains("/proc/self/")) break
        }

        if (resolvedUid == -1) {
            return ProcessInfo("Unknown App", null, null)
        }

        // If the UID is our own app, it is either our loopback API, GUI/PTY app, or one of the guest shell sessions!
        val myUid = context.applicationInfo.uid
        if (resolvedUid == myUid) {
            if (resolvedInode != -1L) {
                val session = findSessionForInode(resolvedInode)
                if (session != null) {
                    val customName = TerminalService.getSessionName(session)
                    val distroName = TerminalService.getSessionDistro(session).substringAfterLast("/")
                    val sessionName = customName ?: "Terminal Session ($distroName)"
                    val procName = findProcNameForInode(resolvedInode) ?: "Shell Client"
                    return ProcessInfo(procName, sessionName, context.packageName)
                }
            }
            return ProcessInfo("NetHunter AI Operator", null, context.packageName)
        }

        // Resolve external Android app package name from UID
        try {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(resolvedUid)
            if (!packages.isNullOrEmpty()) {
                val pkgName = packages[0]
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val appLabel = pm.getApplicationLabel(appInfo).toString()
                return ProcessInfo(appLabel, null, pkgName)
            }
        } catch (e: Exception) {
            // Fallback
        }

        return ProcessInfo("UID: $resolvedUid", null)
    }

    private fun findSessionForInode(inode: Long): TerminalSession? {
        val activeSessions = TerminalService.sessions
        for (session in activeSessions) {
            val shellPid = getSessionPid(session)
            if (shellPid <= 0) continue
            val descendants = getDescendantPids(shellPid)
            for (pid in descendants) {
                if (hasSocket(pid, inode)) {
                    return session
                }
            }
        }
        return null
    }

    private fun findProcNameForInode(inode: Long): String? {
        try {
            val procDir = File("/proc")
            val pids = procDir.list { _, name -> name.all { it.isDigit() } } ?: return null
            for (pidStr in pids) {
                val pid = pidStr.toIntOrNull() ?: continue
                if (hasSocket(pid, inode)) {
                    val cmdlineFile = File("/proc/$pid/cmdline")
                    if (cmdlineFile.exists()) {
                        val cmdline = cmdlineFile.readText().trim('\u0000').trim()
                        if (cmdline.isNotEmpty()) {
                            // Extract just binary name
                            return cmdline.substringAfterLast('/')
                        }
                    }
                    val commFile = File("/proc/$pid/comm")
                    if (commFile.exists()) {
                        val comm = commFile.readText().trim()
                        if (comm.isNotEmpty()) return comm
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun hasSocket(pid: Int, inode: Long): Boolean {
        val fdDir = File("/proc/$pid/fd")
        val fds = fdDir.list() ?: return false
        for (fd in fds) {
            try {
                val symlink = File(fdDir, fd)
                val target = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    java.nio.file.Files.readSymbolicLink(symlink.toPath()).toString()
                } else {
                    symlink.canonicalPath
                }
                if (target.contains("socket:[$inode]")) {
                    return true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return false
    }

    private fun getSessionPid(session: TerminalSession): Int {
        return try {
            val field = session.javaClass.getDeclaredField("mPid")
            field.isAccessible = true
            field.get(session) as Int
        } catch (e: Exception) {
            -1
        }
    }

    private fun getParentPid(pid: Int): Int {
        try {
            val statFile = File("/proc/$pid/stat")
            if (!statFile.exists()) return -1
            val content = statFile.readText()
            val lastParen = content.lastIndexOf(')')
            if (lastParen != -1 && lastParen + 2 < content.length) {
                val afterParen = content.substring(lastParen + 2).trim()
                val fields = afterParen.split(" ")
                if (fields.size >= 2) {
                    return fields[1].toIntOrNull() ?: -1
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return -1
    }

    private fun getDescendantPids(parentPid: Int): Set<Int> {
        val descendants = HashSet<Int>()
        descendants.add(parentPid)
        try {
            val procDir = File("/proc")
            val pids = procDir.list { _, name -> name.all { it.isDigit() } }
            if (pids == null) return descendants
            val pidsList = pids.mapNotNull { it.toIntOrNull() }
            
            val parentMap = HashMap<Int, MutableList<Int>>()
            for (pid in pidsList) {
                val ppid = getParentPid(pid)
                if (ppid != -1) {
                    parentMap.getOrPut(ppid) { ArrayList() }.add(pid)
                }
            }
            
            val queue = java.util.ArrayDeque<Int>()
            queue.add(parentPid)
            while (!queue.isEmpty()) {
                val current = queue.poll() ?: break
                val children = parentMap[current]
                if (children != null) {
                    for (child in children) {
                        if (descendants.add(child)) {
                            queue.add(child)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return descendants
    }

    fun getSessionMemoryUsage(session: TerminalSession): Long {
        val shellPid = getSessionPid(session)
        if (shellPid <= 0) return 0L
        val descendants = getDescendantPids(shellPid)
        var totalRss = 0L
        for (pid in descendants) {
            try {
                val statusFile = File("/proc/$pid/status")
                if (statusFile.exists()) {
                    val lines = statusFile.readLines()
                    for (line in lines) {
                        if (line.startsWith("VmRSS:")) {
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size >= 2) {
                                val valueKb = parts[1].toLongOrNull() ?: 0L
                                totalRss += valueKb * 1024L
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return totalRss
    }
}
