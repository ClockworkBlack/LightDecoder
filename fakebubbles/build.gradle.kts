// A stand-in for the OpenBubbles app, for LightDecoder's on-device Blue Bubbles test only
// (app/src/androidTest/.../BlueBubblesDeviceTest). It is installed under OpenBubbles' beta
// package name and posts message notifications built the way OpenBubbles builds them. It is
// never shipped.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightdecoder.fakebubbles"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bluebubbles.messaging.beta"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
