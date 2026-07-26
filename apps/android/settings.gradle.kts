pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "KoalaCast"

include(":app")

include(":core:model")
include(":core:network")
include(":core:data")
include(":core:player")
include(":core:ui")

include(":feature:onboarding")
include(":feature:discover")
include(":feature:search")
include(":feature:podcast")
include(":feature:episode")
include(":feature:library")
include(":feature:inbox")
include(":feature:profile")
include(":feature:account")
include(":feature:player")
include(":feature:settings")
