plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightdecoder"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.lightdecoder"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        // The decoder is plain software, so it runs on every ABI. These cover
        // phones (64- and 32-bit ARM) and the emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // One native library, so the C++ runtime is linked into it
                // rather than shipped as a second .so.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

// --- unit tests run the real decoder ----------------------------------------
//
// The unit tests decode real HEIC files, so they need the native decoder built
// for the machine running the tests (not for Android). These tasks build it
// with the machine's own CMake and compiler and put it on the tests' library
// path. Needs: cmake, a C++20 compiler, and a JDK (for the JNI headers).

val hostDecoderDir = layout.buildDirectory.dir("host-decoder")

val configureHostDecoder by tasks.registering(Exec::class) {
    description = "Configures the native decoder for the build machine."
    val out = hostDecoderDir.get().asFile
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(File(out, "CMakeCache.txt"))
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine(
        "cmake", "-S", file("src/main/cpp").path, "-B", out.path,
        "-DLD_HOST_BUILD=ON", "-DCMAKE_BUILD_TYPE=Release",
    )
}

val buildHostDecoder by tasks.registering(Exec::class) {
    description = "Builds the native decoder for the build machine."
    dependsOn(configureHostDecoder)
    commandLine(
        "cmake", "--build", hostDecoderDir.get().asFile.path,
        "--target", "lightdecoder",
        "--parallel", Runtime.getRuntime().availableProcessors().toString(),
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostDecoder)
    jvmArgs("-Djava.library.path=${hostDecoderDir.get().asFile.path}")
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
