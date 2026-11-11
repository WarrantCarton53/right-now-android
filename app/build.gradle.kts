plugins {
    id("com.android.application")
}

android {
    namespace = "com.rightnow.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rightnow.app"
        minSdk = 26
        targetSdk = 34
        // Each GitHub build gets a higher number so it installs as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.3." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
    }

    // One fixed key so every new build installs over the old one and keeps your routine.
    signingConfigs {
        create("shared") {
            storeFile = file("rightnow.keystore")
            storePassword = "rightnow"
            keyAlias = "rightnow"
            keyPassword = "rightnow"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
