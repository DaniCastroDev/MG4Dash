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
        // Cible Android 9 comme le reste du système : pas de restrictions d'arrière-plan
        // ni de permissions d'exécution à gérer pour une appli au uid système.
        targetSdk = 28
        versionCode = 20
        versionName = "0.7.2"
    }

    flavorDimensions += "target"
    productFlavors {
        // Voiture : uid système (src/car/AndroidManifest.xml), vraies données carapi.
        create("car") {
            dimension = "target"
            buildConfigField("boolean", "MOCK", "false")
        }
        // Émulateur : même appli sans uid système, données simulées.
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
