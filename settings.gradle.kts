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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/") }
        maven { setUrl("https://artifacts.consensys.net/public/maven/maven/") }
    }
}



rootProject.name = "P2pStream"

include(
    ":app",
)
