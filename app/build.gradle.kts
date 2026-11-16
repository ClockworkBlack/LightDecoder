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
        versionCode = 3
        versionName = "0.3.0"

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

// --- Go bridge: gomobile bind -> app/libs/gmbridge.aar ---------------------
//
// The Google Messages link runs libgm (Go) through gomobile. gomobile and
// gobind are built from the x/mobile version pinned in gmbridge/go.mod, so
// no `gomobile init` or unpinned install is needed. Needs: Go on PATH (the
// toolchain line in go.mod is fetched automatically), the NDK below, and an
// SDK platform >= 26 for gomobile's javac step.

val goModuleDir: File = rootProject.file("gmbridge")
val goToolsDir: File = layout.buildDirectory.dir("gomobile/bin").get().asFile
val goBridgeAar: File = file("libs/gmbridge.aar")

val buildGomobileTools by tasks.registering(Exec::class) {
    description = "Builds gomobile + gobind at the x/mobile version pinned in go.mod."
    val outDir = goToolsDir
    workingDir = goModuleDir
    inputs.files(File(goModuleDir, "go.mod"), File(goModuleDir, "go.sum"))
    outputs.dir(outDir)
    commandLine(
        "go", "build", "-o", outDir.path + File.separator,
        "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind",
    )
}

val buildGoBridge by tasks.registering(Exec::class) {
    description = "Builds the Go Google Messages bridge into app/libs/gmbridge.aar."
    dependsOn(buildGomobileTools)
    val targets = "android/arm64,android/arm,android/amd64" // matches abiFilters
    val javaPkg = "com.lightdecoder.gm" // not com.lightdecoder: the aar's keep rule covers javaPkg.**
    val androidApi = "26" // = minSdk; NDK r28 needs >= 21
    val aar = goBridgeAar
    val tools = goToolsDir
    val sdk = androidComponents.sdkComponents.sdkDirectory
    val ndk = androidComponents.sdkComponents.ndkDirectory // honours ndkVersion

    workingDir = goModuleDir // gomobile reads module info from the working directory
    inputs.files(fileTree(goModuleDir) { include("**/*.go", "go.mod", "go.sum") })
        .withPropertyName("goSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("targets", targets)
    inputs.property("javaPkg", javaPkg)
    inputs.property("androidApi", androidApi)
    outputs.file(aar)
    outputs.file(File(aar.parentFile, aar.nameWithoutExtension + "-sources.jar"))

    environment("ANDROID_HOME", sdk.get().asFile.path)
    environment("ANDROID_NDK_HOME", ndk.get().asFile.path)
    environment(
        "PATH",
        listOf(tools.path, File(System.getProperty("java.home"), "bin").path, System.getenv("PATH"))
            .joinToString(File.pathSeparator), // gobind + javac + go
    )
    commandLine(
        File(tools, "gomobile").path, "bind",
        "-target=$targets", "-androidapi", androidApi, "-javapkg", javaPkg,
        "-trimpath", "-ldflags=-s -w",
        "-o", aar.path, ".",
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The Google Messages bridge (Go, see gmbridge/), built by buildGoBridge above.
    implementation(files(goBridgeAar).builtBy(buildGoBridge))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
