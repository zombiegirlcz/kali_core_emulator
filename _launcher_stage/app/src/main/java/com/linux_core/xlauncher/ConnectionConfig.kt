package com.linux_core.xlauncher

/**
 * Connection target for the Xvnc X server started by the host app
 * (`nh desktop start` → Xvnc :1).
 *
 * The launcher connects to the VNC port (5901) rather than the raw X11 port (6001):
 * RFB gives us incremental framebuffer updates (efficient) and built-in input
 * events, whereas an X11-client framebuffer poll via XGetImage would be slow and
 * cross-UID MIT-SHM would not work.
 */
data class ConnectionConfig(
    val host: String,
    val port: Int,
    val password: String
) {
    companion object {
        /** Defaults match `nh desktop start` (Xvnc :1, 1280x720, pw kali_operator). */
        val DEFAULT = ConnectionConfig("127.0.0.1", 5901, "kali_operator")
    }
}
