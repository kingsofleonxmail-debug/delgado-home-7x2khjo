plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.delgado.family"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.delgado.family"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            // The signing key is never stored in the repo in readable form. GitHub unlocks it at build time
            // with the KEYSTORE_PASSWORD secret (see .github/workflows/android.yml).
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "delgado"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
