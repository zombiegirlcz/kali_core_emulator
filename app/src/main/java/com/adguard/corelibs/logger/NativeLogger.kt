package com.adguard.corelibs.logger

import android.util.Log

object NativeLogger {
    interface Facade {
        fun error(msg: String, vararg args: Any?)
        fun info(msg: String, vararg args: Any?)
    }

    class SimpleFacade(val clazz: Class<*>) : Facade {
        override fun error(msg: String, vararg args: Any?) {
            Log.e(clazz.simpleName, String.format(msg, *args))
        }

        override fun info(msg: String, vararg args: Any?) {
            Log.i(clazz.simpleName, String.format(msg, *args))
        }
    }

    fun getFacade(clazz: Class<*>): Facade {
        return SimpleFacade(clazz)
    }
}
