// OnboardingScreens.kt
package com.blackcloudgroup.securemessenger

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/**
 * Shared scaffold for every onboarding step: a step-progress indicator up
 * top, the step's own content in the middle, and a Back/Next (or
 * Back/Finish) footer. Individual screens only provide [content] and decide
 * whether [onNext] is currently enabled.
 */
@Composable
private fun OnboardingScaffold(
    stepIndex: Int,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    nextLabel: String,
    canGoNext: Boolean,
    onNext: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (canGoBack) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Button(onClick = onNext, enabled = canGoNext) { Text(nextLabel) }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            LinearProgressIndicator(
                progress = { (stepIndex + 1f) / OnboardingRoute.steps.size },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Step ${stepIndex + 1} of ${OnboardingRoute.steps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        }
    }
}

// ---------------------------------------------------------------------
// Step 1: Welcome / threat-model selection
// ---------------------------------------------------------------------

@Composable
fun WelcomeScreen(
    state: OnboardingUiState,
    onSelect: (ThreatModel) -> Unit,
    onStartBridgeScan: () -> Unit,
    onUseLocalFallback: () -> Unit,
    onNext: () -> Unit
) {
    OnboardingScaffold(
        stepIndex = 0,
        title = "Welcome to Black Cloud Secure Messenger",
        canGoBack = false,
        onBack = {},
        nextLabel = "Next",
        canGoNext = state.canLeaveWelcome,
        onNext = onNext
    ) {
        Text(
            text = "Choose how your messages reach the people you talk to. " +
                "You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        ThreatModelOption(
            title = "Direct & Private",
            description = "Connect straight to another device over the network you're " +
                "both on. No servers in between.",
            selected = state.selectedThreatModel == ThreatModel.PEER_TO_PEER,
            onClick = { onSelect(ThreatModel.PEER_TO_PEER) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ThreatModelOption(
            title = "Local Mesh Bridge",
            description = "Relay through nearby trusted devices to reach someone outside " +
                "direct range. Useful when a direct connection isn't possible.",
            selected = state.selectedThreatModel == ThreatModel.LOCAL_MESH_BRIDGE,
            onClick = { onSelect(ThreatModel.LOCAL_MESH_BRIDGE) }
        )

        if (state.selectedThreatModel == ThreatModel.LOCAL_MESH_BRIDGE) {
            Spacer(modifier = Modifier.height(16.dp))
            BridgeDiscoverySection(
                state = state,
                onStartScan = onStartBridgeScan,
                onUseFallback = onUseLocalFallback
            )
        }
    }
}

/**
 * Looks for a nearby Project Canopy / Heltec / nRF52840 hardware bridge over
 * BLE. Entirely optional: whatever happens here, the Next button above stays
 * enabled the moment a threat model is picked (see
 * [OnboardingUiState.canLeaveWelcome]) — a missing bridge, denied
 * permission, or disabled Bluetooth never blocks onboarding, it just means
 * the app falls back to Wi-Fi/cellular P2P directly.
 */
@Composable
private fun BridgeDiscoverySection(
    state: OnboardingUiState,
    onStartScan: () -> Unit,
    onUseFallback: () -> Unit
) {
    val context = LocalContext.current
    var hasRequestedPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasRequestedPermission = true
        if (grants.values.all { it }) {
            onStartScan()
        } else {
            onUseFallback()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Hardware bridge", fontWeight = FontWeight.SemiBold)

            when (val scanState = state.bleScanState) {
                is BleDeviceState.Idle -> {
                    Text(
                        text = "Optional: look for a nearby bridge device to extend your range.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Button(onClick = {
                        if (BlePermissions.hasAllRequired(context)) {
                            onStartScan()
                        } else {
                            permissionLauncher.launch(BlePermissions.requiredForScan())
                        }
                    }) {
                        Text("Scan for bridge device")
                    }
                }
                is BleDeviceState.Scanning -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning for nearby bridges…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is BleDeviceState.BridgeFound -> {
                    Text(
                        text = "Found: ${scanState.device.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is BleDeviceState.ScanTimedOutNoBridgeFound -> {
                    Text(
                        text = "No bridge device found nearby. Continuing with Wi-Fi/cellular directly.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is BleDeviceState.BluetoothDisabled -> {
                    Text(
                        text = "Bluetooth is off. Continuing with Wi-Fi/cellular directly.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is BleDeviceState.Unsupported -> {
                    Text(
                        text = "This device doesn't support BLE bridge hardware. Using Wi-Fi/cellular directly.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is BleDeviceState.PermissionMissing -> {
                    Text(
                        text = if (hasRequestedPermission) {
                            "Bridge scanning permission was denied. Continuing with Wi-Fi/cellular directly."
                        } else {
                            "Bridge scanning needs Bluetooth permission."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is BleDeviceState.Error -> {
                    Text(
                        text = "Couldn't scan for a bridge (${scanState.message}). Continuing with Wi-Fi/cellular directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (state.discoveredBridges.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${state.discoveredBridges.size} bridges found nearby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.usingLocalFallback) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ Using local Wi-Fi/cellular peer-to-peer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThreatModelOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Step 2: Local identity generation
// ---------------------------------------------------------------------

@Composable
fun IdentityScreen(
    state: OnboardingUiState,
    onDisplayNameChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    OnboardingScaffold(
        stepIndex = 1,
        title = "Create your Secure Identity",
        canGoBack = true,
        onBack = onBack,
        nextLabel = "Next",
        canGoNext = state.canLeaveIdentity,
        onNext = onNext
    ) {
        Text(
            text = "Your Secure Identity is how other devices recognize you. " +
                "It's generated on this device and never leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        OutlinedTextField(
            value = state.displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Display name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(20.dp))

        when {
            state.isGeneratingIdentity -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Generating your Secure Identity…")
                }
            }
            state.localFingerprint != null -> {
                Text(
                    text = "Secure Identity ready",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = state.localFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            else -> {
                Button(onClick = onGenerate) { Text("Generate Secure Identity") }
            }
        }

        state.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------------------------------------------------------------------
// Step 3: Biometric & Keystore security setup
// ---------------------------------------------------------------------

@Composable
fun SecurityScreen(
    state: OnboardingUiState,
    onCheckAvailability: () -> Unit,
    onAuthSucceeded: () -> Unit,
    onAuthFailed: (String) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { onCheckAvailability() }

    OnboardingScaffold(
        stepIndex = 2,
        title = "Secure this device",
        canGoBack = true,
        onBack = onBack,
        nextLabel = "Next",
        canGoNext = state.canLeaveSecurity,
        onNext = onNext
    ) {
        Text(
            text = "Your messages are stored in a Device Encrypted Vault. You can " +
                "optionally require your fingerprint or face to unlock it.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        when (state.biometricAvailability) {
            BiometricAvailability.CHECKING -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            BiometricAvailability.AVAILABLE -> {
                if (state.biometricEnabled) {
                    Text(
                        text = "Biometric unlock enabled",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Button(onClick = {
                        promptBiometricAuth(
                            context = context,
                            onSuccess = onAuthSucceeded,
                            onFailure = onAuthFailed
                        )
                    }) {
                        Text("Set up biometric unlock")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSkip) { Text("Skip for now") }
                }
            }
            BiometricAvailability.NOT_ENROLLED -> {
                Text(
                    text = "No fingerprint or face is set up on this device yet. " +
                        "You can add one in your device Settings and turn this on later.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            BiometricAvailability.NO_HARDWARE, BiometricAvailability.UNAVAILABLE -> {
                Text(
                    text = "Biometric unlock isn't available on this device. " +
                        "Your Device Encrypted Vault is still protected.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "✓ Device Encrypted Vault configured",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Wraps androidx.biometric's BiometricPrompt, which requires a
 * FragmentActivity host (its only constructors take FragmentActivity or
 * Fragment — there is no ComponentActivity overload in 1.1.0).
 *
 * REQUIRED CHANGE, not just an assumption: androidx.fragment.app.FragmentActivity
 * extends androidx.activity.ComponentActivity — the inheritance runs that
 * direction, not the reverse — so a plain ComponentActivity (what
 * MainActivity currently extends) is NOT a FragmentActivity, and the cast
 * below would fail every time. Whichever activity hosts OnboardingNavGraph
 * must extend FragmentActivity instead of ComponentActivity. See the
 * MainActivity change in this same commit.
 */
private fun promptBiometricAuth(
    context: android.content.Context,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit
) {
    val activity = context as? FragmentActivity
    if (activity == null) {
        onFailure("Biometric setup requires an activity context")
        return
    }

    val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock with biometrics")
        .setSubtitle("Confirm it's you to enable biometric unlock")
        .setNegativeButtonText("Cancel")
        .build()

    val biometricPrompt = androidx.biometric.BiometricPrompt(
        activity,
        executor,
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: androidx.biometric.BiometricPrompt.AuthenticationResult
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // EXPLICIT FAILURE PATH: user cancel (negative button / back)
                // is not a real failure, just report everything else.
                if (errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED
                ) {
                    onFailure(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // A single failed match (wrong finger, etc.) — let the
                // prompt's own UI handle retry; don't bubble this up as a
                // terminal failure.
            }
        }
    )

    biometricPrompt.authenticate(promptInfo)
}

// ---------------------------------------------------------------------
// Step 4: Seed & key encapsulation backup
// ---------------------------------------------------------------------

@Composable
fun BackupScreen(
    state: OnboardingUiState,
    onSelect: (BackupChoice) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    OnboardingScaffold(
        stepIndex = 3,
        title = "Back up your Secure Identity",
        canGoBack = true,
        onBack = onBack,
        nextLabel = "Next",
        canGoNext = state.canLeaveBackup,
        onNext = onNext
    ) {
        Text(
            text = "If you lose this device, your backup choice determines whether " +
                "your Secure Identity can be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        BackupOption(
            title = "Device Encrypted Vault",
            description = "Kept only on this device, protected by your Device Encrypted " +
                "Vault. Recommended for most people.",
            selected = state.backupChoice == BackupChoice.DEVICE_ENCRYPTED_VAULT,
            onClick = { onSelect(BackupChoice.DEVICE_ENCRYPTED_VAULT) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        BackupOption(
            title = "Encrypted Export",
            description = "Save an encrypted backup file yourself, for recovery on a new " +
                "device.",
            selected = state.backupChoice == BackupChoice.ENCRYPTED_EXPORT,
            onClick = { onSelect(BackupChoice.ENCRYPTED_EXPORT) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (!state.backupConfirmed) {
            Button(onClick = onConfirm, enabled = state.backupChoice != null) {
                Text("Confirm choice")
            }
        } else {
            Text(
                text = "Backup choice confirmed",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        state.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BackupOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Step 5: Final readiness / node handshake self-test
// ---------------------------------------------------------------------

@Composable
fun ReadinessScreen(
    state: OnboardingUiState,
    onRunCheck: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        if (state.readinessChecklist.isEmpty()) onRunCheck()
    }

    OnboardingScaffold(
        stepIndex = 4,
        title = "You're almost ready",
        canGoBack = true,
        onBack = onBack,
        nextLabel = "Finish",
        canGoNext = state.isReadyToFinish,
        onNext = onFinish
    ) {
        if (state.isRunningReadinessCheck) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Checking your setup…")
            }
        } else {
            LazyColumn {
                items(state.readinessChecklist) { item -> ReadinessRow(item) }
            }

            if (!state.isReadyToFinish && state.readinessChecklist.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRunCheck) { Text("Re-check") }
            }
        }

        state.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem) {
    // Plain text glyphs rather than androidx.compose.material.icons.Icons:
    // that artifact (material-icons-core) isn't a declared dependency of
    // this module (only material3, ui, ui-graphics, ui-tooling-preview
    // are), and the rest of the codebase already uses plain "✓"/text
    // markers (see SecurityScreen's vault-configured line) rather than
    // pulling in a new icon dependency for two glyphs.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.passed) "✓" else "✗",
            color = if (item.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = item.label)
    }
}
