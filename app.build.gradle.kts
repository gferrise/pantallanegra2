plugins {
    id("com.android.application")
}

android {
    namespace = "com.gabriel.pantallanegra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gabriel.pantallanegra"
        minSdk = 26          // A31 y S23 están muy por encima
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // instalable directo, sin keystore propio
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Sin dependencias: solo el framework de Android. APK de pocos KB.
