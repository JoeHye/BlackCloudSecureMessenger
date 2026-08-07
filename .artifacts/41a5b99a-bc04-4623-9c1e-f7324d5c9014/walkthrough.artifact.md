# Walkthrough: Fixing Dependency Resolution

## Changes Made
- Added `maven { url = uri("https://jitpack.io") }` to `settings.gradle.kts` to enable resolving dependencies from JitPack.
- Updated the dependency declaration in `app/build.gradle.kts` from `io.libp2p:jvm-libp2p:1.1.0` to `com.github.libp2p:jvm-libp2p:1.1.0` to correctly match JitPack's repository convention.

## Validation Results
- The project now syncs successfully.
- The build process now proceeds past the dependency resolution phase.
