package com.blackcloudgroup.securemessenger

/**
 * Type-safe destinations for the onboarding flow. Each screen is a singleton
 * [OnboardingRoute] rather than a raw string, so navigation call sites
 * (`navController.navigate(OnboardingRoute.Identity.route)`) and `when`
 * branches over [OnboardingRoute] are compiler-checked and exhaustive.
 *
 * NOTE on scope (flagging the alternative not taken): Navigation Compose
 * 2.8+ supports fully serialized type-safe routes (`@Serializable` route
 * objects passed directly to `navigate(route)`/`composable<T>`), which also
 * lets you carry typed arguments on the back stack. This project doesn't
 * pass any arguments between onboarding screens — all shared state lives in
 * [OnboardingUiState] inside the scoped [OnboardingViewModel] instead — so
 * there's nothing for that mechanism to buy here, and pulling it in would
 * mean adding the kotlinx-serialization Gradle plugin and a new dependency
 * version I can't verify against this project's pinned Kotlin/AGP versions
 * without a real build. This sealed-class-of-string-routes approach gets
 * the same practical type safety (no raw string literals at call sites, no
 * typos, exhaustive `when`) with zero new dependencies.
 */
sealed class OnboardingRoute(val route: String) {

    /** Welcome + threat-model selection (peer-to-peer vs. local mesh bridge). */
    object Welcome : OnboardingRoute("onboarding/welcome")

    /** Local identity generation: display name + local fingerprint. */
    object Identity : OnboardingRoute("onboarding/identity")

    /** Biometric & Keystore security setup. */
    object Security : OnboardingRoute("onboarding/security")

    /** Seed & key encapsulation backup choice. */
    object Backup : OnboardingRoute("onboarding/backup")

    /** Final readiness check / node handshake self-test. */
    object Readiness : OnboardingRoute("onboarding/readiness")

    companion object {
        /** Ordered steps, used to compute progress and to drive Back/Next navigation. */
        val steps: List<OnboardingRoute> = listOf(Welcome, Identity, Security, Backup, Readiness)

        fun fromRoute(route: String?): OnboardingRoute? = steps.firstOrNull { it.route == route }
    }
}
