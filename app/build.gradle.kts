import java.io.IOException
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- release signing ---------------------------------------------------------
//
// The release build is the one to install and hand out. It is not debuggable,
// so adb cannot read its data (the message archive, the Google Messages
// session) or attach a debugger to it. The debug build is for development only.
//
// The release key is read from environment variables (CI secrets) or from
// Gradle properties (-P..., or ~/.gradle/gradle.properties):
//   LIGHTDECODER_KEYSTORE           lightdecoder.keystore          keystore file
//   LIGHTDECODER_KEYSTORE_PASSWORD  lightdecoder.keystorePassword
//   LIGHTDECODER_KEY_ALIAS          lightdecoder.keyAlias
//   LIGHTDECODER_KEY_PASSWORD       lightdecoder.keyPassword       (default: keystore password)
// Without a keystore the release APK is signed with the local debug key, so a
// local `assembleRelease` still installs. It stays non-debuggable either way.

fun signingSetting(env: String, prop: String): String? =
    sequenceOf(providers.environmentVariable(env), providers.gradleProperty(prop))
        .mapNotNull { p -> p.orNull?.takeIf { it.isNotBlank() } } // an unset CI secret is ""
        .firstOrNull()

val releaseKeystore: File? =
    signingSetting("LIGHTDECODER_KEYSTORE", "lightdecoder.keystore")?.let { rootProject.file(it) }

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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                fun required(env: String, prop: String): String = signingSetting(env, prop)
                    ?: throw GradleException("Release keystore is set but $env / $prop is not.")
                if (!releaseKeystore.isFile) {
                    throw GradleException("Release keystore not found: $releaseKeystore")
                }
                storeFile = releaseKeystore
                storePassword = required("LIGHTDECODER_KEYSTORE_PASSWORD", "lightdecoder.keystorePassword")
                keyAlias = required("LIGHTDECODER_KEY_ALIAS", "lightdecoder.keyAlias")
                keyPassword = signingSetting("LIGHTDECODER_KEY_PASSWORD", "lightdecoder.keyPassword")
                    ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            // Development only: a debuggable build lets anyone with adb on the
            // phone read its data, including the archive and the session. It
            // installs as a separate app (own data, own Keystore keys), so it
            // can never replace, or be mistaken for, the release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false // the default, stated because the data's protection depends on it
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

if (releaseKeystore == null) {
    tasks.named { it == "packageRelease" }.configureEach {
        doFirst { logger.warn("No release keystore set: app-release.apk is signed with the local debug key.") }
    }
}

// --- Go bridge: gomobile bind -> app/libs/gmbridge.aar ---------------------
//
// The Google Messages link runs libgm (Go) through gomobile. gomobile and
// gobind are built from the x/mobile version pinned in gmbridge/go.mod, so
// no `gomobile init` or unpinned install is needed. Needs: Go 1.21 or newer
// (see goHint), the NDK below, and an SDK platform >= 26 for gomobile's javac
// step.
//
// Every Go step runs with GOTOOLCHAIN set to the `toolchain` line of
// gmbridge/go.mod, so libgojni.so is always built by that exact, patched Go
// release. GOTOOLCHAIN=auto would not do that: gomobile binds in a temporary
// module whose go.mod says only `go 1.26`, which any go1.26.x satisfies.

val goModuleDir: File = rootProject.file("gmbridge")
val goToolsDir: File = layout.buildDirectory.dir("gomobile/bin").get().asFile
val goBridgeAar: File = file("libs/gmbridge.aar")

val goToolchain: String = File(goModuleDir, "go.mod").readLines()
    .firstOrNull { it.startsWith("toolchain ") }?.removePrefix("toolchain ")?.trim()
    ?: throw GradleException("gmbridge/go.mod has no toolchain line; the Go bridge build needs one.")

// Optional override: -Pgmbridge.go=/path/to/go, or gmbridge.go=... in ~/.gradle/gradle.properties.
val goExe: String = providers.gradleProperty("gmbridge.go").orNull?.takeIf { it.isNotBlank() } ?: "go"
val goHint = "The Google Messages bridge needs Go 1.21 or newer: it builds with $goToolchain " +
    "(the toolchain line of gmbridge/go.mod), which Go downloads by itself unless it is the Go " +
    "installed. Put Go on the PATH that Gradle sees, or set gmbridge.go=/path/to/go " +
    "(-P on the command line, or in ~/.gradle/gradle.properties)."

val buildGomobileTools by tasks.registering(Exec::class) {
    description = "Builds gomobile + gobind at the x/mobile version pinned in go.mod."
    val outDir = goToolsDir
    val modDir = goModuleDir
    val exe = goExe
    val toolchain = goToolchain
    val hint = goHint
    workingDir = modDir
    inputs.files(File(modDir, "go.mod"), File(modDir, "go.sum"))
    inputs.property("goToolchain", toolchain)
    outputs.dir(outDir)
    environment("GOTOOLCHAIN", toolchain)
    executable = exe
    args(
        "build", "-o", outDir.path + File.separator,
        "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind",
    )
    doFirst {
        // Fail early, with a hint, when Go is missing or too old to switch to
        // the pinned toolchain (Go before 1.21 ignores GOTOOLCHAIN).
        val (code, out) = try {
            val p = ProcessBuilder(exe, "env", "GOVERSION").directory(modDir)
                .redirectErrorStream(true)
                .apply { environment()["GOTOOLCHAIN"] = toolchain }
                .start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor() to text.trim()
        } catch (e: IOException) {
            throw GradleException("Could not run '$exe'. $hint", e)
        }
        if (code != 0) throw GradleException("'$exe env GOVERSION' failed: $out\n$hint")
        // The last line: a toolchain download may print "go: downloading ..." first.
        val version = out.lineSequence().last().trim().substringBefore(' ')
        if (version != toolchain) {
            throw GradleException("'$exe' runs $version instead of $toolchain. $hint")
        }
    }
}

val buildGoBridge by tasks.registering(Exec::class) {
    description = "Builds the Go Google Messages bridge into app/libs/gmbridge.aar."
    val targets = "android/arm64,android/arm,android/amd64" // matches abiFilters
    val javaPkg = "com.lightdecoder.gm" // not com.lightdecoder: the aar's keep rule covers javaPkg.**
    val androidApi = "26" // = minSdk; NDK r28 needs >= 21
    // 16 KB-aligned LOAD segments (Android 15+ page sizes, Play's 16 KB rule)
    // whatever the NDK linker's default; harmless on 32-bit ARM.
    val ldflags = "-s -w -extldflags=-Wl,-z,max-page-size=16384"
    val aar = goBridgeAar
    val tools = goToolsDir
    val toolchain = goToolchain
    val goBin = File(goExe).takeIf { it.isAbsolute }?.parentFile?.path // gomobile runs `go` from PATH
    val sdk = androidComponents.sdkComponents.sdkDirectory
    val ndk = androidComponents.sdkComponents.ndkDirectory // honours ndkVersion

    workingDir = goModuleDir // gomobile reads module info from the working directory
    inputs.files(buildGomobileTools).withPropertyName("gomobileTools") // also orders the two tasks
    inputs.files(fileTree(goModuleDir) { include("**/*.go", "go.mod", "go.sum") })
        .withPropertyName("goSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("targets", targets)
    inputs.property("javaPkg", javaPkg)
    inputs.property("androidApi", androidApi)
    inputs.property("goToolchain", toolchain)
    // The environment is not a task input, so name the NDK here. Its path
    // includes its version, so changing ndkVersion links libgojni.so again.
    inputs.property("ndkDir", ndk.map { it.asFile.path })
    outputs.file(aar)
    outputs.file(File(aar.parentFile, aar.nameWithoutExtension + "-sources.jar"))

    environment("ANDROID_HOME", sdk.get().asFile.path)
    environment("ANDROID_NDK_HOME", ndk.get().asFile.path)
    environment("GOTOOLCHAIN", toolchain) // inherited by every `go` that gomobile runs
    environment(
        "PATH",
        listOfNotNull(tools.path, goBin, File(System.getProperty("java.home"), "bin").path, System.getenv("PATH"))
            .joinToString(File.pathSeparator), // gobind + go + javac
    )
    commandLine(
        File(tools, "gomobile").path, "bind",
        "-target=$targets", "-androidapi", androidApi, "-javapkg", javaPkg,
        "-trimpath", "-ldflags=$ldflags",
        "-o", aar.path, ".",
    )
}

// --- licence notices in the APK ----------------------------------------------
//
// NOTICE and the licence texts of everything compiled into the app go into its
// assets (NOTICE, licenses/*.txt) for the About screen to show. The Go modules
// are read from the build info of the libgojni.so files just built, so a new or
// updated dependency cannot be missed, and one without a licence file fails the
// build. It also checks that each libgojni.so was built with the pinned Go.

val generatedLicensesDir: File = layout.buildDirectory.dir("generated/licenses").get().asFile

val bundleLicenses by tasks.registering {
    description = "Collects NOTICE and the licence texts of the code in the APK into its assets."
    val out = generatedLicensesDir
    val aar = goBridgeAar
    val modDir = goModuleDir
    val exe = goExe
    val toolchain = goToolchain
    val notice = rootProject.file("NOTICE")
    val fixed = listOf( // asset name, heading, licence file
        Triple("00-lightdecoder.txt", "LightDecoder (this app)", rootProject.file("LICENSE")),
        Triple("10-libheif.txt", "libheif (in liblightdecoder.so)", rootProject.file("third_party/libheif/COPYING")),
        Triple("11-libde265.txt", "libde265 (in liblightdecoder.so)", rootProject.file("third_party/libde265/COPYING")),
    )
    inputs.files(buildGoBridge).withPropertyName("goBridge")
    inputs.file(notice).withPropertyName("notice")
    inputs.files(fixed.map { it.third }).withPropertyName("licenseFiles")
    inputs.property("goToolchain", toolchain)
    outputs.dir(out)

    doLast {
        fun go(dir: File, vararg args: String): String {
            val p = ProcessBuilder(exe, *args).directory(dir).redirectErrorStream(true)
                .apply { environment()["GOTOOLCHAIN"] = toolchain }.start()
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("'go ${args.joinToString(" ")}' failed: $text")
            return text
        }
        val licenceName = Regex("(?i)^(licen[cs]e|copying|unlicense)([._-].*)?$")
        val extraName = Regex("(?i)^(notice|patents)([._-].*)?$")
        fun licenceText(heading: String, files: List<File>): String = buildString {
            append(heading).append("\n")
            for (f in files) append("\n==== ").append(f.name).append(" ====\n\n").append(f.readText().trimEnd()).append("\n")
        }

        out.deleteRecursively()
        val licenses = File(out, "licenses").apply { mkdirs() }
        notice.copyTo(File(out, "NOTICE"))
        for ((name, heading, file) in fixed) {
            File(licenses, name).writeText(licenceText(heading, listOf(file)))
        }

        // The Go modules linked into libgojni.so, from each ABI's build info.
        val work = temporaryDir.apply { listFiles()?.forEach { it.deleteRecursively() } }
        val linked = sortedMapOf<String, String>() // module path -> version
        ZipFile(aar).use { zip ->
            val libs = zip.entries().asSequence().filter { it.name.endsWith("/libgojni.so") }.toList()
            if (libs.isEmpty()) throw GradleException("No libgojni.so in $aar")
            for (entry in libs) {
                val so = File(work, entry.name.replace('/', '_'))
                zip.getInputStream(entry).use { input -> so.outputStream().use { input.copyTo(it) } }
                val info = go(work, "version", "-m", so.path).lines()
                val built = info.first { it.startsWith(so.path + ": ") }.substringAfterLast(": ").trim()
                if (built != toolchain) {
                    throw GradleException("${entry.name} was built with $built, not $toolchain from gmbridge/go.mod")
                }
                for (line in info) {
                    val f = line.split('\t')
                    if (f.size >= 4 && f[1] == "dep") linked[f[2]] = f[3]
                }
            }
        }

        // Where each module's source is (its licence files are at its root).
        val modules = go(
            modDir, "list", "-m", "-f",
            "{{.Main}}\t{{.Path}}\t{{.Version}}\t{{with .Replace}}{{.Dir}}{{else}}{{.Dir}}{{end}}", "all",
        ).lines().map { it.split('\t') }.filter { it.size == 4 }.associateBy { it[1] }
        for ((path, version) in linked) {
            val m = modules[path]
                ?: throw GradleException("Go module $path is in libgojni.so but not in gmbridge's module list")
            if (m[0] == "true") continue // gmbridge itself: this app's code, under its LICENSE
            if (m[2] != version) throw GradleException("Go module $path: libgojni.so has $version, go.mod ${m[2]}")
            val dir = File(m[3])
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            val texts = files.filter { licenceName.matches(it.name) }
            if (texts.isEmpty()) {
                throw GradleException("No licence file in $dir for Go module $path $version; add its licence before shipping it")
            }
            val name = "20-go-" + path.replace(Regex("[^A-Za-z0-9.-]+"), "_") + ".txt"
            File(licenses, name).writeText(
                licenceText("$path $version (Go module, in libgojni.so)", texts + files.filter { extraName.matches(it.name) }),
            )
        }

        // The Go runtime and standard library, from the pinned toolchain.
        val goroot = File(go(work, "env", "GOROOT").trim().lines().last())
        val goFiles = listOf("LICENSE", "PATENTS").map { File(goroot, it) }.filter { it.isFile }
        if (goFiles.none { it.name == "LICENSE" }) throw GradleException("No LICENSE in GOROOT $goroot")
        File(licenses, "30-go.txt").writeText(
            licenceText("The Go runtime and standard library, $toolchain (in libgojni.so)", goFiles),
        )
    }
}

android.sourceSets["main"].assets.srcDir(generatedLicensesDir)
tasks.named { it == "preBuild" }.configureEach { dependsOn(bundleLicenses) }

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
