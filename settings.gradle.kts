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

rootProject.name = "driftzero"

include(":navigation-core")
project(":navigation-core").projectDir = file("packages/navigation-core")

include(":android-app")
project(":android-app").projectDir = file("apps/android/app")
