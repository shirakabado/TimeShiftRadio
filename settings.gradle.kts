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
        // LAN 認証ゲートウェイの共通ライブラリ（shirakabado/lan-gateway-android）。
        // 読み取りに ~/.gradle/gradle.properties の gpr.user / gpr.key（read:packages）が要る
        maven {
            url = uri("https://maven.pkg.github.com/shirakabado/lan-gateway-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("net.enjoytech.langateway") }
        }
    }
}

rootProject.name = "TimeShiftRadio"
include(":app")
 