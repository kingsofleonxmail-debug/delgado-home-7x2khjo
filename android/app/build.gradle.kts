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
            // Same key every build so new versions install over old ones.
            storeFile = file("../delgado-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "delgado-family-app"
            keyAlias = "delgado"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "delgado-family-app"
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
