package com.delivery.app.utils

import android.util.Log
import com.delivery.app.data.local.ErrorLogDao
import com.delivery.app.data.model.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppLogger {
    private const val MAX_LOGS = 500
    private var dao: ErrorLogDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(errorLogDao: ErrorLogDao) {
        dao = errorLogDao
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        insert("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        insert("INFO", tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        Log.w(tag, message, tr)
        insert("WARN", tag, message, tr?.let { stackTraceToString(it) })
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e(tag, message, tr)
        insert("ERROR", tag, message, tr?.let { stackTraceToString(it) })
    }

    private fun stackTraceToString(tr: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        tr.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun insert(level: String, tag: String, message: String, stackTrace: String? = null) {
        dao?.let { d ->
            scope.launch {
                d.insert(ErrorLog(level = level, tag = tag, message = message, stackTrace = stackTrace))
                d.trimTo(MAX_LOGS)
            }
        }
    }
}
