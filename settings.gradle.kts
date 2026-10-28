pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":vehicle-customization-api", ":changan-hud")
project(":vehicle-customization-api").projectDir = file("customizations/api")
project(":changan-hud").projectDir = file("customizations/changan-hud")
include(":changan-projection")
project(":changan-projection").projectDir = file("customizations/changan-projection")
include(":common")
include(":app-lifecycle")
project(":app-lifecycle").projectDir = file("customizations/app-lifecycle")
include(":compact-logging")
project(":compact-logging").projectDir = file("customizations/compact-logging")
include(":changan-audio")
project(":changan-audio").projectDir = file("customizations/changan-audio")
include(":mobile")
include(":automotive")
include(":shared")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
