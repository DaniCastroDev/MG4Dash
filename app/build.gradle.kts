plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mg4dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mg4dash"
        minSdk = 28
        // Cible Android 9 comme le reste du systÃ¨me : pas de restrictions d'arriÃ¨re-plan
        // ni de permissions d'exÃ©cution Ã  gÃ©rer pour une appli au uid systÃ¨me.
        targetSdk = 28
        versionCode = 14
        versionName = "0.4.6"
    }

    flavorDimensions += "target"
    productFlavors {
        // Voiture : uid systÃ¨me (src/car/AndroidManifest.xml), vraies donnÃ©es carapi.
        create("car") {
            dimension = "target"
            buildConfigField("boolean", "MOCK", "false")
        }
        // Ã‰mulateur : mÃªme appli sans uid systÃ¨me, donnÃ©es simulÃ©es.
        create("emulator") {
            dimension = "target"
            applicationIdSuffix = ".emu"
            buildConfigField("boolean", "MOCK", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions", "QueryAllPackagesPermission")
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
