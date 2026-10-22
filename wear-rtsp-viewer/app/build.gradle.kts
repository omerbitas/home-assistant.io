plugins {
    id("com.android.application")
}

android {
    namespace = "com.omerbitas.rtspwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omerbitas.rtspwatch"
        minSdk = 30 // Wear OS 3+ (Galaxy Watch Ultra ships with Wear OS 5)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be sideloaded as-is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
