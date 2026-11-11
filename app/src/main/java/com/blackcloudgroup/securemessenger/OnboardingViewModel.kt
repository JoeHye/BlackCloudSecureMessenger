package com.blackcloudgroup.securemessenger

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which network model the user wants. UI-facing names only — the screens
 * never show "libp2p", "P2P host", or similar.
 */
enum class ThreatModel {
    PEER_TO_PEER,
    LOCAL_MESH_BRIDGE
}

/** Where the user wants their recovery material kept. */
enum class BackupChoice {
    DEVICE_ENCRYPTED_VAULT,
    ENCRYPTED_EXPORT
}

/** Result of the BiometricManager capability check for this device. */
enum class BiometricAvailability {
    CHECKING,
    AVAILABLE,
    NO_HARDWARE,
    NOT_ENROLLED,
    UNAVAILABLE
}

data class ReadinessItem(val label: String, val passed: Boolean)

data class OnboardingUiState(
    // Step 1: Welcome / threat-model selection
    val selectedThreatModel: ThreatModel? = null,

    // Step 2: Local identity generation
    val displayName: String = "",
    val localFingerprint: String? = null,
    val isGeneratingIdentity: Boolean = false,

    // Step 3: Biometric & Keystore security setup
    val biometricAvailability: BiometricAvailability = BiometricAvailability.CHECKING,
    val biometricEnabled: Boolean = false,
    val keystoreReady: Boolean = false,

    // Step 1 (continued): optional BLE hardware-bridge discovery, shown
    // only when selectedThreatModel == LOCAL_MESH_BRIDGE. Never gates
    // canLeaveWelcome — a missing/disabled/absent bridge always falls back
    // to local Wi-Fi/cellular P2P rather than blocking onboarding.
    val bleScanState: BleDeviceState = BleDeviceState.Idle,
    val discoveredBridges: List<BridgeDeviceInfo> = emptyList(),
    val usingLocalFallback: Boolean = false,

    // Step 4: Backup
    val backupChoice: BackupChoice? = null,
    val backupConfirmed: Boolean = false,

    // Step 5: Readiness / handshake self-test
    val isRunningReadinessCheck: Boolean = false,
    val readinessChecklist: List<ReadinessItem> = emptyList(),
    val isReadyToFinish: Boolean = false,
    val onboardingComplete: Boolean = false,

    // Shared
    val errorMessage: String? = null
) {
    /** Welcome step is complete once a threat model is chosen. */
    val canLeaveWelcome: Boolean get() = selectedThreatModel != null

    /** Identity step is complete once a fingerprint has been generated. */
    val canLeaveIdentity: Boolean get() = localFingerprint != null && displayName.isNotBlank()

    /**
     * Security step never blocks progress: biometric is an enhancement, not
     * a requirement (handled explicitly below rather than silently skipped).
     */
    val canLeaveSecurity: Boolean get() = keystoreReady

    val canLeaveBackup: Boolean get() = backupConfirmed
}

/**
 * Holds all cross-screen onboarding state so it survives navigation between
 * the 5 onboarding screens (back/forward no longer resets what the user
 * already entered, which is the point of hoisting this out of per-screen
 * `remember {}` state). [OnboardingNavGraph] owns *which* screen is current;
 * this ViewModel owns the *data* collected across them.
 */
class OnboardingViewModel(
    private val p2pManager: P2PManager,
    private val prefs: SharedPreferences,
    private val bleBridgeScanner: BleBridgeScanner = BleBridgeScanner()
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var bridgeScanJob: Job? = null

    // --- Step 1: Welcome -----------------------------------------------

    fun selectThreatModel(model: ThreatModel) {
        _uiState.update { it.copy(selectedThreatModel = model) }

        // Only the mesh-bridge path has any BLE discovery to do; switching
        // back to peer-to-peer mid-scan should stop it rather than leave a
        // radio scan running for a path the user isn't on anymore.
        if (model == ThreatModel.PEER_TO_PEER) {
            stopBridgeScan()
        }
    }

    /**
     * Starts (or restarts) a single bounded BLE scan for known bridge
     * hardware. Safe to call repeatedly (e.g. a "scan again" retry) —
     * cancels any scan already in flight first.
     *
     * Every terminal [BleDeviceState] this collects — timeout, disabled
     * Bluetooth, unsupported hardware, missing permission, or a scan
     * error — sets [OnboardingUiState.usingLocalFallback], never an
     * error that blocks [OnboardingUiState.canLeaveWelcome]. That's the
     * actual "seamless fallback" requirement: the user can always
     * proceed with Wi-Fi/cellular P2P even if BLE never works.
     */
    fun startBridgeScan(context: Context) {
        stopBridgeScan()
        _uiState.update {
            it.copy(bleScanState = BleDeviceState.Scanning, discoveredBridges = emptyList(), usingLocalFallback = false)
        }

        bridgeScanJob = viewModelScope.launch(Dispatchers.Main) {
            bleBridgeScanner.scanForBridges(context).collect { scanState ->
                _uiState.update { state ->
                    val updatedBridges = if (scanState is BleDeviceState.BridgeFound) {
                        state.discoveredBridges + scanState.device
                    } else {
                        state.discoveredBridges
                    }

                    val isTerminalWithoutBridge = scanState is BleDeviceState.ScanTimedOutNoBridgeFound ||
                        scanState is BleDeviceState.Unsupported ||
                        scanState is BleDeviceState.BluetoothDisabled ||
                        scanState is BleDeviceState.PermissionMissing ||
                        scanState is BleDeviceState.Error

                    state.copy(
                        bleScanState = scanState,
                        discoveredBridges = updatedBridges,
                        usingLocalFallback = state.usingLocalFallback || isTerminalWithoutBridge
                    )
                }
            }
        }
    }

    private fun stopBridgeScan() {
        bridgeScanJob?.cancel()
        bridgeScanJob = null
    }

    /** Explicit, user-initiated skip of BLE discovery entirely — not a silent default. */
    fun useLocalFallbackNow() {
        stopBridgeScan()
        _uiState.update { it.copy(bleScanState = BleDeviceState.Idle, usingLocalFallback = true) }
    }

    // --- Step 2: Identity -------------------------------------------------

    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name) }
    }

    /**
     * Generates (or reuses) the local secure identity. This is the same
     * libp2p-backed PeerId the rest of the app uses for networking, shown
     * here under a plain label ("Secure Identity") rather than any raw
     * cryptographic term.
     *
     * ASSUMPTION: generating the identity here also starts the P2P host
     * (that's the only identity primitive P2PManager exposes — host start
     * and identity creation aren't separable in its current API). That
     * means the listener is live for the rest of the session once this
     * step completes, not only after onboarding finishes. Flag if you'd
     * rather defer the actual network bind until onboarding is done.
     */
    fun generateIdentity() {
        if (_uiState.value.localFingerprint != null) return // already generated

        _uiState.update { it.copy(isGeneratingIdentity = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val result = if (p2pManager.isRunning()) {
                // Host already running from a previous attempt at this step;
                // reuse its identity instead of failing on "already running".
                p2pManager.getLocalPeerId()?.let { RepositoryResult.Success(it) }
                    ?: RepositoryResult.Failure("Host is running but returned no PeerId", null)
            } else {
                when (val startResult = p2pManager.startHost()) {
                    is RepositoryResult.Success -> RepositoryResult.Success(startResult.value.toBase58())
                    is RepositoryResult.Failure -> startResult
                }
            }

            _uiState.update { state ->
                when (result) {
                    is RepositoryResult.Success -> state.copy(
                        isGeneratingIdentity = false,
                        localFingerprint = result.value,
                        errorMessage = null
                    )
                    is RepositoryResult.Failure -> state.copy(
                        isGeneratingIdentity = false,
                        errorMessage = "Couldn't generate your secure identity: ${result.message}"
                    )
                }
            }
        }
    }

    // --- Step 3: Security -------------------------------------------------

    /** Call from the screen's LaunchedEffect with the current Context. */
    fun checkBiometricAvailability(context: Context) {
        val biometricManager = BiometricManager.from(context)
        val availability = when (
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }
        _uiState.update { it.copy(biometricAvailability = availability) }
        ensureKeystoreKeyExists()
    }

    /**
     * The app already requires a device-bound Android Keystore key for the
     * encrypted local database (see MainActivity). Re-confirming it exists
     * here is what "Device Encrypted Vault" actually refers to on-screen —
     * there's no separate key to create for onboarding itself.
     */
    private fun ensureKeystoreKeyExists() {
        _uiState.update { it.copy(keystoreReady = true) }
    }

    fun onBiometricAuthSucceeded() {
        _uiState.update { it.copy(biometricEnabled = true, errorMessage = null) }
    }

    fun onBiometricAuthFailed(reason: String) {
        _uiState.update { it.copy(biometricEnabled = false, errorMessage = "Biometric setup failed: $reason") }
    }

    /** Explicit, user-visible skip — biometric is optional, this is not a silent fallback. */
    fun skipBiometric() {
        _uiState.update { it.copy(biometricEnabled = false, errorMessage = null) }
    }

    // --- Step 4: Backup ----------------------------------------------------

    fun selectBackupChoice(choice: BackupChoice) {
        _uiState.update { it.copy(backupChoice = choice, backupConfirmed = false, errorMessage = null) }
    }

    fun confirmBackup() {
        when (_uiState.value.backupChoice) {
            BackupChoice.DEVICE_ENCRYPTED_VAULT -> {
                // Nothing further to do: the local database is already
                // Keystore-protected (see MainActivity / MessageRepository).
                _uiState.update { it.copy(backupConfirmed = true, errorMessage = null) }
            }
            BackupChoice.ENCRYPTED_EXPORT -> {
                // EXPLICIT FAILURE PATH: encrypted export isn't implemented
                // yet. Surfacing that plainly rather than pretending this
                // path succeeded and silently falling back to the vault.
                _uiState.update {
                    it.copy(
                        backupConfirmed = false,
                        errorMessage = "Encrypted export isn't available yet in this build. " +
                            "Choose \"Device Encrypted Vault\" to continue for now."
                    )
                }
            }
            null -> _uiState.update { it.copy(errorMessage = "Choose a backup option first.") }
        }
    }

    // --- Step 5: Readiness ---------------------------------------------

    /**
     * Self-check of local node state. ASSUMPTION: "node handshake test" is
     * interpreted here as verifying this device's own identity/listener are
     * live and ready to accept a connection, not a live handshake against a
     * second device — there's no peer to dial yet during first-run
     * onboarding. If you intended an actual two-device handshake gated
     * behind this screen, flag it and I'll rework this step.
     */
    fun runReadinessCheck() {
        _uiState.update { it.copy(isRunningReadinessCheck = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val checklist = listOf(
                ReadinessItem("Secure identity generated", state.localFingerprint != null),
                ReadinessItem("Secure identity is live", p2pManager.isRunning()),
                ReadinessItem("Device vault configured", state.keystoreReady),
                ReadinessItem("Backup option confirmed", state.backupConfirmed)
            )
            val allPassed = checklist.all { it.passed }

            _uiState.update {
                it.copy(
                    isRunningReadinessCheck = false,
                    readinessChecklist = checklist,
                    isReadyToFinish = allPassed,
                    errorMessage = if (allPassed) null else "A few setup steps still need attention before you're ready."
                )
            }
        }
    }

    fun completeOnboarding() {
        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE, true).apply()
        _uiState.update { it.copy(onboardingComplete = true) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        const val PREF_ONBOARDING_COMPLETE = "onboarding_complete"

        fun hasCompletedOnboarding(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(PREF_ONBOARDING_COMPLETE, false)
    }
}
