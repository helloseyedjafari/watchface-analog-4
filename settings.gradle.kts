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
        // the Google face validator pulls AXML from JitPack
        maven("https://jitpack.io") { content { includeGroup("com.github.xgouchet") } }
    }
}
rootProject.name = "AnalogWatchFace"
include(":app", ":protocol", ":facegen", ":packjni", ":phone", ":watch")
