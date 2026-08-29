package com.linux_core.xlauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Standalone X11 desktop launcher.
 *
 * Connects to the Xvnc X server started by the host app (`nh desktop start`,
 * display :1) over the VNC port (5901) and renders the framebuffer. This app
 * only renders — the actual X server and desktop session live in the proot
 * guest managed by `com.linux_core`.
 *
 * Connection overrides can be passed via intent extras: host, port, password.
 */
class LauncherActivity : Activity() {

    private lateinit var view: FramebufferView
    private lateinit var status: TextView
    private var client: VncClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        view = FramebufferView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val root = FrameLayout(this).apply {
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 24, 24, 24) })
        }
        setContentView(root)

        view.pointerListener = object : FramebufferView.PointerListener {
            override fun onPointer(fbX: Int, fbY: Int, mask: Int) {
                client?.pointerEvent(fbX, fbY, mask)
            }
        }

        val config = intent.extras?.let { e ->
            ConnectionConfig(
                e.getString("host") ?: ConnectionConfig.DEFAULT.host,
                e.getInt("port", ConnectionConfig.DEFAULT.port),
                e.getString("password") ?: ConnectionConfig.DEFAULT.password
            )
        } ?: ConnectionConfig.DEFAULT

        status.text = "Connecting to ${config.host}:${config.port} …"
        startConnection(config)
    }

    private fun startConnection(config: ConnectionConfig) {
        Thread {
            val c = VncClient()
            client = c
            c.connect(config, object : VncClient.Listener {
                override fun onConnected(width: Int, height: Int) = runOnUiThread {
                    view.setFramebufferSize(width, height)
                    status.visibility = View.GONE
                }

                override fun onFramebuffer(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
                    view.updateRegion(x, y, w, h, pixels)
                }

                override fun onDesktopSize(w: Int, h: Int) = runOnUiThread {
                    view.setFramebufferSize(w, h)
                }

                override fun onDisconnected() = runOnUiThread {
                    if (status.visibility != View.VISIBLE) {
                        status.visibility = View.VISIBLE
                        status.text = "Disconnected."
                    }
                }

                override fun onError(t: Throwable) = runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "Error: ${t.message}"
                }
            })
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
    }
}
