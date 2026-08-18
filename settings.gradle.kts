pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
        google()
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "UacSniSpooferMobile"
include(":app")
