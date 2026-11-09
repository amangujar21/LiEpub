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

rootProject.name = "StoryWeaver"

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":feature:create")
include(":feature:library")
include(":feature:settings")
include(":source:literotica")
include(":epub")
