pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        // Added for io.libp2p:jvm-libp2p
        maven { url = uri("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/") }
        maven { url = uri("https://artifacts.consensys.net/public/maven/maven/") }
    }
}

rootProject.name = "BlackCloudSecureMessenger"
include(":app")