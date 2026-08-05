package com.adguard.corelibs.logger

import android.util.Log

object NativeLogger {
    interface Callback {
        fun log(level: Int, message: String)
    }

    interface Facade {
        fun error(msg: String, vararg args: Any?)
        fun info(msg: String, vararg args: Any?)
    }

    @Volatile
    private var callback: Callback? = null

    @JvmStatic
    fun registerCallback(cb: Callback) {
        callback = cb
    }

    @JvmStatic
    fun log(level: Int, message: String) {
        val cb = callback
        if (cb != null) {
            cb.log(level, message)
            return
        }
        val tag = "AdGuardNative"
        when (level) {
            0 -> Log.e(tag, message)
            1 -> Log.w(tag, message)
            2 -> Log.i(tag, message)
            3 -> Log.d(tag, message)
            else -> Log.v(tag, message)
        }
    }

    class SimpleFacade(val clazz: Class<*>) : Facade {
        override fun error(msg: String, vararg args: Any?) {
            val formatted = if (args.isEmpty()) msg else {
                try {
                    String.format(msg, *args)
                } catch (e: Exception) {
                    msg
                }
            }
            Log.e(clazz.simpleName, formatted)
        }

        override fun info(msg: String, vararg args: Any?) {
            val formatted = if (args.isEmpty()) msg else {
                try {
                    String.format(msg, *args)
                } catch (e: Exception) {
                    msg
                }
            }
            Log.i(clazz.simpleName, formatted)
        }
    }

    fun getFacade(clazz: Class<*>): Facade {
        return SimpleFacade(clazz)
    }
}
