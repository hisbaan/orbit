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
    }
}

rootProject.name = "orbit"
include(":app")
include(":diagnostics", ":providers", ":agent", ":ytmusic", ":weather", ":homeassistant")
include(":audio", ":speech", ":tools-android")
