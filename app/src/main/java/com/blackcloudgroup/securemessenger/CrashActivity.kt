package com.blackcloudgroup.securemessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Debug-only crash screen. Launched by [SecureMessengerApplication]'s uncaught-exception
 * handler in place of the OS's generic "app keeps stopping" dialog, so the actual Kotlin
 * exception and stack trace are visible on-device without depending on `adb logcat` timing.
 *
 * Not reachable in release builds: nothing ever starts this activity unless the debug
 * crash handler in SecureMessengerApplication fires, and that handler is only installed
 * when BuildConfig.DEBUG is true.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read once at launch. SecureMessengerApplication.lastCrashReport is set
        // immediately before this activity is started, in the same process.
        val report = SecureMessengerApplication.lastCrashReport
            ?: "No crash report was captured. This screen should only be reached " +
                "after SecureMessengerApplication's uncaught-exception handler sets " +
                "lastCrashReport, so an empty value here points at a bug in that " +
                "handler rather than at the original crash."

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashScreen(report = report)
                }
            }
        }
    }
}

@Composable
private fun CrashScreen(report: String) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Black Cloud Secure Messenger crashed (debug build)",
            style = MaterialTheme.typography.titleMedium
        )

        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = report,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = { copyToClipboard(context, report) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy stack trace")
        }

        Button(
            onClick = {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Close app")
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    // EXPLICIT FAILURE PATH: ClipboardManager lookup can return null on some
    // OEM builds/configurations; don't silently no-op the button press.
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboardManager == null) {
        Toast.makeText(context, "Clipboard service unavailable on this device", Toast.LENGTH_SHORT).show()
        return
    }
    clipboardManager.setPrimaryClip(ClipData.newPlainText("Crash report", text))
    Toast.makeText(context, "Stack trace copied", Toast.LENGTH_SHORT).show()
}
