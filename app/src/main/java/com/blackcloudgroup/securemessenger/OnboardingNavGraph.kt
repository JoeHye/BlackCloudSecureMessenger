// OnboardingNavGraph.kt
package com.blackcloudgroup.securemessenger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Hosts the 5 onboarding screens behind a single NavHost. All shared state
 * lives in [viewModel] (see [OnboardingUiState]) — this graph only owns
 * *which* screen is current, driven by simple forward/back navigation
 * along [OnboardingRoute.steps] rather than any branching (every user goes
 * through every step, in order; the only per-screen choice is the data they
 * enter, not which screen comes next).
 *
 * @param onOnboardingComplete invoked once the user finishes step 5. The
 * caller (MainActivity) decides what that means — typically swapping to
 * the main ChatScreen navigation.
 */
@Composable
fun OnboardingNavGraph(
    viewModel: OnboardingViewModel,
    onOnboardingComplete: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val state by viewModel.uiState.collectAsState()
    // NavController.context exists but isn't public API — read it here
    // instead, where LocalContext is available because this is a
    // @Composable function.
    val context = LocalContext.current

    fun goToNextStep(from: OnboardingRoute) {
        val currentIndex = OnboardingRoute.steps.indexOf(from)
        val next = OnboardingRoute.steps.getOrNull(currentIndex + 1)
        if (next != null) {
            navController.navigate(next.route)
        }
        // else: from == last step; ReadinessScreen's onFinish handles
        // completion directly rather than navigating past the last step.
    }

    fun goBack() {
        navController.popBackStack()
    }

    NavHost(navController = navController, startDestination = OnboardingRoute.Welcome.route) {
        composable(OnboardingRoute.Welcome.route) {
            WelcomeScreen(
                state = state,
                onSelect = viewModel::selectThreatModel,
                onNext = { goToNextStep(OnboardingRoute.Welcome) }
            )
        }

        composable(OnboardingRoute.Identity.route) {
            IdentityScreen(
                state = state,
                onDisplayNameChange = viewModel::updateDisplayName,
                onGenerate = viewModel::generateIdentity,
                onBack = ::goBack,
                onNext = { goToNextStep(OnboardingRoute.Identity) }
            )
        }

        composable(OnboardingRoute.Security.route) {
            SecurityScreen(
                state = state,
                onCheckAvailability = { viewModel.checkBiometricAvailability(context) },
                onAuthSucceeded = viewModel::onBiometricAuthSucceeded,
                onAuthFailed = viewModel::onBiometricAuthFailed,
                onSkip = viewModel::skipBiometric,
                onBack = ::goBack,
                onNext = { goToNextStep(OnboardingRoute.Security) }
            )
        }

        composable(OnboardingRoute.Backup.route) {
            BackupScreen(
                state = state,
                onSelect = viewModel::selectBackupChoice,
                onConfirm = viewModel::confirmBackup,
                onBack = ::goBack,
                onNext = { goToNextStep(OnboardingRoute.Backup) }
            )
        }

        composable(OnboardingRoute.Readiness.route) {
            ReadinessScreen(
                state = state,
                onRunCheck = viewModel::runReadinessCheck,
                onFinish = {
                    viewModel.completeOnboarding()
                    onOnboardingComplete()
                },
                onBack = ::goBack
            )
        }
    }
}
