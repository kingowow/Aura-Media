pluginManagement {
    repositories {
        // ─── منابع رسمی ───────────────────────────────────────────────
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()

        // ─── Mirror چین (Aliyun) ──────────────────────────────────────
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }

        // ─── Mirror های ایرانی ───────────────────────────────────────
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://archive.ito.gov.ir/gradle/maven-plugin/") }

        // ─── Myket - آخرین گزینه ─────────────────────────────────────
        maven { url = uri("https://maven.myket.ir") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // ─── منابع رسمی ───────────────────────────────────────────────
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        // ─── Mirror چین (Aliyun) ──────────────────────────────────────
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }

        // ─── Mirror های ایرانی ───────────────────────────────────────
        maven { url = uri("https://maven.devneeds.ir") }
        maven { url = uri("https://gradle.iranrepo.ir") }
        maven { url = uri("https://gradle.jamko.ir") }
        maven { url = uri("https://en-mirror.ir") }
        maven { url = uri("https://archive.ito.gov.ir/gradle/maven-plugin/") }

        // ─- آخرین گزینه ──────
        maven { url = uri("https://maven.myket.ir") }
    }
}

rootProject.name = "hibiki"

include(":app")
include(":parsers")