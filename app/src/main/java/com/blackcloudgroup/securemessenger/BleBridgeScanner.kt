// BleBridgeScanner.kt
package com.blackcloudgroup.securemessenger

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One emission of BLE bridge-discovery state. Terminal states (everything
 * except [Scanning] and [BridgeFound]) close the underlying flow — callers
 * decide what "terminal" means for onboarding (always: fall back to local
 * Wi-Fi/cellular P2P without blocking progression).
 */
sealed class BleDeviceState {
    /** Scan has not started yet. */
    object Idle : BleDeviceState()

    /** Scan is actively running (up to [BleBridgeScanner.SCAN_TIMEOUT_MS]). */
    object Scanning : BleDeviceState()

    /** A recognized bridge was found. The scan keeps running after this —
     * more than one bridge can be emitted before the scan ends. */
    data class BridgeFound(val device: BridgeDeviceInfo) : BleDeviceState()

    /** Scan ran the full timeout window and found no recognized bridge. */
    object ScanTimedOutNoBridgeFound : BleDeviceState()

    /** Device has no Bluetooth adapter, or no BLE scanner, at all. */
    object Unsupported : BleDeviceState()

    /** Adapter exists but Bluetooth is turned off. */
    object BluetoothDisabled : BleDeviceState()

    /** One or more required runtime permissions aren't granted. */
    data class PermissionMissing(val missingPermissions: List<String>) : BleDeviceState()

    /** EXPLICIT FAILURE PATH: anything else the platform BLE stack reported. */
    data class Error(val message: String) : BleDeviceState()
}

/** A discovered Project Canopy / Heltec / nRF52840 hardware bridge. */
data class BridgeDeviceInfo(
    val name: String,
    val address: String,
    val rssiDbm: Int
)

/**
 * Runtime-permission plumbing for BLE scanning. Android 12 (API 31) split
 * the old ACCESS_FINE_LOCATION-gated BLE permission model into dedicated
 * BLUETOOTH_SCAN / BLUETOOTH_CONNECT runtime permissions; devices below
 * API 31 still use the legacy location-permission path. Both are declared
 * in AndroidManifest.xml with the appropriate maxSdkVersion/API gating.
 */
object BlePermissions {

    /** Permissions a caller must hold before [BleBridgeScanner.scanForBridges] can start a scan. */
    fun requiredForScan(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // BLUETOOTH_CONNECT isn't strictly required to *start* a scan,
            // but reading BluetoothDevice.name from a ScanResult on API 31+
            // throws SecurityException without it, and showing an unnamed
            // "Unknown device" result isn't useful for matching known
            // bridge hardware. Requesting both up front keeps the actual
            // scan callback free of permission branching.
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(context: Context): List<String> =
        requiredForScan().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAllRequired(context: Context): Boolean = missingPermissions(context).isEmpty()
}

/**
 * Reactive BLE discovery for external LoRa-mesh hardware bridges (Project
 * Canopy, Heltec, nRF52840-based nodes). Scans are always time-boxed to
 * [SCAN_TIMEOUT_MS] — this is a deliberate resource guard, not a UX nicety:
 * an unbounded BLE scan keeps the radio hot and drains battery, and a stuck
 * scan with no caller-side timeout is a real failure mode on some OEM BLE
 * stacks.
 */
class BleBridgeScanner {

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L

        /**
         * Name substrings (case-insensitive) that identify a supported
         * bridge. Matched against BluetoothDevice.name rather than a
         * ScanFilter device-name filter, because ScanFilter.setDeviceName
         * only supports an exact match, not a prefix/substring — this list
         * is deliberately permissive to catch firmware builds that put the
         * marker anywhere in the advertised name.
         *
         * ASSUMPTION: these are the only three product lines in scope per
         * the task description. If there's a specific advertised-name
         * convention (firmware-defined prefix, manufacturer-data UUID) for
         * Project Canopy nodes specifically, flag it — matching on
         * manufacturer data would be far more reliable than a name
         * substring and is a straightforward follow-up once that spec is
         * available.
         */
        private val KNOWN_BRIDGE_NAME_MARKERS = listOf("canopy", "heltec", "nrf52840", "nrf52")
    }

    /**
     * Starts a single bounded BLE scan and emits [BleDeviceState] as it
     * progresses. The scan (and the underlying BluetoothLeScanner callback)
     * is stopped when the flow is cancelled or completes — collecting this
     * from a coroutine scope that's cancelled (e.g. a ViewModel's
     * viewModelScope, or leaving the screen) tears the radio scan down
     * cleanly via awaitClose.
     */
    fun scanForBridges(context: Context): Flow<BleDeviceState> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            trySend(BleDeviceState.Unsupported)
            close()
            return@callbackFlow
        }

        if (!adapter.isEnabled) {
            trySend(BleDeviceState.BluetoothDisabled)
            close()
            return@callbackFlow
        }

        val missing = BlePermissions.missingPermissions(context)
        if (missing.isNotEmpty()) {
            trySend(BleDeviceState.PermissionMissing(missing))
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            // EXPLICIT FAILURE PATH: adapter.isEnabled was true above but
            // the scanner is still null on some OEM stacks (observed when
            // Bluetooth is mid-toggle). Report it rather than crashing on
            // a null startScan() receiver.
            trySend(BleDeviceState.Unsupported)
            close()
            return@callbackFlow
        }

        // ScanCallback fires on a Binder/platform thread, not necessarily
        // this flow's coroutine context; the timeout job below reads
        // bridgeFoundCount from whatever dispatcher the collector is using.
        // Plain var/mutableSetOf would be a real data race across those two
        // threads, so these are thread-safe rather than coroutine-confined.
        val bridgeFoundCount = AtomicInteger(0)
        val seenAddresses = ConcurrentHashMap.newKeySet<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (!seenAddresses.add(device.address)) return // already reported this address

                val deviceName = try {
                    device.name
                } catch (e: SecurityException) {
                    // Shouldn't happen: BLUETOOTH_CONNECT was confirmed
                    // granted before startScan() below. Treat as "no name"
                    // rather than crashing the scan callback.
                    null
                }

                if (deviceName != null &&
                    KNOWN_BRIDGE_NAME_MARKERS.any { deviceName.contains(it, ignoreCase = true) }
                ) {
                    bridgeFoundCount.incrementAndGet()
                    trySend(
                        BleDeviceState.BridgeFound(
                            BridgeDeviceInfo(name = deviceName, address = device.address, rssiDbm = result.rssi)
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(BleDeviceState.Error("BLE scan failed (platform error code $errorCode)"))
                close()
            }
        }

        trySend(BleDeviceState.Scanning)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, scanSettings, callback)
        } catch (e: SecurityException) {
            // Defensive: BlePermissions.missingPermissions() already
            // checked above, but don't let a platform-level permission
            // revocation race crash the caller.
            trySend(BleDeviceState.PermissionMissing(BlePermissions.requiredForScan().toList()))
            close()
            return@callbackFlow
        }

        val timeoutJob = launch {
            delay(SCAN_TIMEOUT_MS)
            if (bridgeFoundCount.get() == 0) {
                trySend(BleDeviceState.ScanTimedOutNoBridgeFound)
            }
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                // Permission may have been revoked mid-scan (e.g. the user
                // turned it off in system settings while scanning); the
                // scan is being torn down either way, nothing further to do.
            }
        }
    }
}
