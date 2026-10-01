package com.blackcloudgroup.securemessenger

import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application entry point. In debug builds only, installs an uncaught-exception
 * handler that routes the real stack trace to an on-screen [CrashActivity] instead
 * of letting the OS show a bare "App keeps stopping" dialog with no diagnostic info.
 *
 * Release builds are intentionally left on the platform's default crash behavior
 * (this handler is never installed when BuildConfig.DEBUG is false), so this adds
 * no behavior change for anything shipped outside of local/dev testing.
 */
class SecureMessengerApplication : Application() {

    companion object {
        private const val TAG = "SecureMessengerApp"

        // Holds the most recent crash's formatted report for CrashActivity to read.
        // In-memory only (not persisted): the crashing process is killed shortly
        // after CrashActivity launches, and this is read while that same process
        // is still alive, before it dies.
        @Volatile
        var lastCrashReport: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            installDebugCrashHandler()
        }
        // else: release builds keep the platform default uncaught-exception handler.
    }

    private fun installDebugCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = formatCrashReport(thread, throwable)
                Log.e(TAG, "Uncaught exception on thread '${thread.name}':\n$report")
                lastCrashReport = report

                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)

                // Give the new activity a moment to actually start before this
                // process is torn down. Best-effort only: if this fails for any
                // reason, we still fall through to killing the process below
                // rather than leaving the app in a half-crashed state.
                try {
                    Thread.sleep(500)
                } catch (interrupted: InterruptedException) {
                    Log.w(TAG, "Crash-handler delay interrupted before CrashActivity could render", interrupted)
                }
            } catch (handlerFailure: Exception) {
                // EXPLICIT FAILURE PATH: the crash handler itself failed (e.g.
                // CrashActivity couldn't be started). Log it and fall back to
                // whatever handler the OS/runtime originally had, rather than
                // silently swallowing the failure.
                Log.e(TAG, "Debug crash handler itself failed while reporting a crash", handlerFailure)
                defaultHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // Terminate this process explicitly so the OS doesn't attempt to
            // resume a corrupted app state. CrashActivity runs in a fresh
            // launch of the same process's task, so it is unaffected by this.
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    private fun formatCrashReport(thread: Thread, throwable: Throwable): String {
        val stringWriter = StringWriter()
        PrintWriter(stringWriter).use { throwable.printStackTrace(it) }
        return buildString {
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            append(stringWriter.toString())
        }
    }
}
