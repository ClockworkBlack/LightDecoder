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
// The release key is read from environment variables or from Gradle
// properties (-P..., or ~/.gradle/gradle.properties):
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
        versionCode = 16
        versionName = "0.5.8"

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
            // The smart cut-out's runtime is 22 MB for x86_64 (emulators, a few Chromebooks):
            // left out there, where the sticker maker falls back to its plain-background cut.
            excludes += setOf("lib/x86_64/libonnxruntime.so", "lib/x86_64/libonnxruntime4j_jni.so")
        }
        resources {
            // Both Jakarta Mail jars carry the same licence files; their text ships in
            // assets/licenses (bundleLicenses), shown under About > Licences.
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
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
// The Google Messages link runs libgm (Go) through gomobile, and the WhatsApp
// link runs whatsmeow (gmbridge/wa); both packages are bound into the one aar,
// because two Go runtimes in one app clash. gomobile and
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
// It may name any Go 1.21+ binary, including a golang.org/dl wrapper such as go1.26.11.
val goExe: String = providers.gradleProperty("gmbridge.go").orNull?.takeIf { it.isNotBlank() } ?: "go"
val goHint = "The Google Messages bridge needs Go 1.21 or newer: it builds with $goToolchain " +
    "(the toolchain line of gmbridge/go.mod), which Go downloads by itself unless it is the Go " +
    "installed. Put Go on the PATH that Gradle sees, or set gmbridge.go to any Go 1.21+ binary " +
    "or golang.org/dl wrapper, e.g. gmbridge.go=/path/to/go or gmbridge.go=$goToolchain " +
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
    description = "Builds the Go bridges (Google Messages, WhatsApp) into app/libs/gmbridge.aar."
    // Every Go package bound into the aar: gmbridge itself and the WhatsApp
    // bridge. Java: com.lightdecoder.gm.gmbridge.* and com.lightdecoder.gm.wa.*.
    val goPackages = listOf(".", "./wa")
    val targets = "android/arm64,android/arm,android/amd64" // matches abiFilters
    val javaPkg = "com.lightdecoder.gm" // not com.lightdecoder: the aar's keep rule covers javaPkg.**
    val androidApi = "26" // = minSdk; NDK r28 needs >= 21
    // 16 KB-aligned LOAD segments (Android 15+ page sizes, Play's 16 KB rule)
    // whatever the NDK linker's default; harmless on 32-bit ARM.
    val ldflags = "-s -w -extldflags=-Wl,-z,max-page-size=16384"
    val aar = goBridgeAar
    val tools = goToolsDir
    val toolchain = goToolchain
    val exe = goExe
    val modDir = goModuleDir
    val hint = goHint
    val sdk = androidComponents.sdkComponents.sdkDirectory
    val ndk = androidComponents.sdkComponents.ndkDirectory // honours ndkVersion

    workingDir = goModuleDir // gomobile reads module info from the working directory
    inputs.files(buildGomobileTools).withPropertyName("gomobileTools") // also orders the two tasks
    // **/go.mod: the patched libgm in gmbridge/third_party is a module of its own.
    inputs.files(fileTree(goModuleDir) { include("**/*.go", "**/go.mod", "**/go.sum") })
        .withPropertyName("goSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("targets", targets)
    inputs.property("goPackages", goPackages)
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
    commandLine(
        File(tools, "gomobile").path, "bind",
        "-target=$targets", "-androidapi", androidApi, "-javapkg", javaPkg,
        "-trimpath", "-ldflags=$ldflags",
        "-o", aar.path, *goPackages.toTypedArray(),
    )
    doFirst {
        // gomobile and gobind always run `go` from PATH, whatever gmbridge.go
        // names (e.g. a golang.org/dl wrapper called go1.26.11, with an older
        // Go or none at all on PATH). So ask the configured Go for the root of
        // the pinned toolchain (after any toolchain switch) and put that root's
        // bin first on PATH, so every `go` they run is exactly that binary.
        fun run(cmd: String, vararg args: String): String {
            val (code, out) = try {
                val p = ProcessBuilder(cmd, *args).directory(modDir).redirectErrorStream(true)
                    .apply { environment()["GOTOOLCHAIN"] = toolchain }.start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor() to text.trim()
            } catch (e: IOException) {
                throw GradleException("Could not run '$cmd'. $hint", e)
            }
            if (code != 0) throw GradleException("'$cmd ${args.joinToString(" ")}' failed: $out\n$hint")
            return out.lineSequence().last().trim() // a toolchain download may print "go: downloading ..." first
        }
        val goroot = File(run(exe, "env", "GOROOT"))
        val goBin = File(goroot, "bin")
        val go = File(goBin, if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go")
        if (!go.isFile) throw GradleException("'$exe env GOROOT' gave $goroot, which has no $go. $hint")
        val version = run(go.path, "env", "GOVERSION").substringBefore(' ')
        if (version != toolchain) throw GradleException("$go runs $version instead of $toolchain. $hint")
        environment("GOROOT", goroot.path) // not one inherited from a different Go
        environment(
            "PATH",
            listOfNotNull(tools.path, goBin.path, File(System.getProperty("java.home"), "bin").path, System.getenv("PATH"))
                .joinToString(File.pathSeparator), // gobind + go + javac
        )
    }
}

// --- licence notices in the APK ----------------------------------------------
//
// NOTICE and the licence texts of everything compiled into the app go into its
// assets (NOTICE, licenses/*.txt) for the About screen to show. The Go modules
// are read from the build info of the libgojni.so files just built, so a new or
// updated dependency cannot be missed, and one without a licence file fails the
// build. It also checks that each libgojni.so was built with the pinned Go.
// The Android libraries compiled into classes.dex are read from the release
// runtime classpath; all are Apache-2.0, and one outside the known Apache-2.0
// groups fails the build until its licence is added here.

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
    // AndroidX, Material Components, Kotlin and kotlinx.coroutines ship no
    // licence text in their artifacts (and AGP drops META-INF licence files),
    // so the Apache-2.0 text they are under is kept in the repository.
    val apacheLicense = rootProject.file("third_party/licenses/Apache-2.0.txt")
    // Jakarta Mail for Android (the Gmail tab's IMAP/SMTP): EPL-2.0 or GPL-2.0 with the Classpath Exception.
    val mailLicense = rootProject.file("third_party/licenses/EPL-2.0-OR-GPL-2.0-CPE-jakarta-mail.txt")
    val mailNotice = rootProject.file("third_party/licenses/NOTICE-jakarta-mail.md")
    // ONNX Runtime (the sticker maker's smart cut-out): MIT.
    val onnxLicense = rootProject.file("third_party/licenses/MIT-onnxruntime.txt")
    val onnxLibrary = Regex("com\\.microsoft\\.onnxruntime:onnxruntime-android")
    val mailLibrary = Regex("com\\.sun\\.mail:(android-mail|android-activation)")
    val apacheLibrary = Regex(
        "(androidx\\.[^:]+|com\\.google\\.android\\.material|org\\.jetbrains\\.kotlinx?):[^:]+" +
            "|org\\.jetbrains:annotations|org\\.jspecify:jspecify" +
            "|com\\.google\\.guava:listenablefuture|com\\.google\\.errorprone:error_prone_annotations" +
            "|com\\.google\\.zxing:core",
    )
    val androidLibraries = provider { configurations.getByName("releaseRuntimeClasspath") }.map { classpath ->
        classpath.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .sorted()
    }
    inputs.files(buildGoBridge).withPropertyName("goBridge")
    inputs.file(notice).withPropertyName("notice")
    inputs.files(fixed.map { it.third } + apacheLicense + mailLicense + mailNotice + onnxLicense).withPropertyName("licenseFiles")
    // Licence files and PATCHES.md of Go modules replaced by a copy in the repository.
    inputs.files(fileTree(File(goModuleDir, "third_party")) { exclude("**/*.go") })
        .withPropertyName("replacedGoModules").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("goToolchain", toolchain)
    inputs.property("androidLibraries", androidLibraries)
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
        // A module replaced by a patched copy says what was changed here (AGPL §5a).
        val patchesName = Regex("(?i)^patches([._-].*)?$")
        fun licenceText(heading: String, files: List<File>, intro: String = ""): String = buildString {
            append(heading).append("\n")
            if (intro.isNotEmpty()) append("\n").append(intro.trimEnd()).append("\n")
            for (f in files) append("\n==== ").append(f.name).append(" ====\n\n").append(f.readText().trimEnd()).append("\n")
        }

        out.deleteRecursively()
        val licenses = File(out, "licenses").apply { mkdirs() }
        notice.copyTo(File(out, "NOTICE"))
        for ((name, heading, file) in fixed) {
            File(licenses, name).writeText(licenceText(heading, listOf(file)))
        }

        // The Android libraries compiled into classes.dex.
        val libraries = androidLibraries.get()
        if (libraries.isEmpty()) throw GradleException("No Android libraries on the release runtime classpath")
        val mail = libraries.filter { mailLibrary.matches(it.substringBeforeLast(':')) }
        val onnx = libraries.filter { onnxLibrary.matches(it.substringBeforeLast(':')) }
        val unknown = libraries.filterNot { apacheLibrary.matches(it.substringBeforeLast(':')) || it in mail || it in onnx }
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "Not known to be Apache-2.0: ${unknown.joinToString()}; add its licence text to bundleLicenses " +
                    "(app/build.gradle.kts) and NOTICE before shipping it",
            )
        }
        if (!Regex("Apache License\\s+Version 2\\.0, January 2004").containsMatchIn(apacheLicense.readText())) {
            throw GradleException("$apacheLicense is not the Apache License 2.0 text")
        }
        File(licenses, "40-android-libraries.txt").writeText(
            licenceText(
                "AndroidX, Material Components, Kotlin standard library, kotlinx.coroutines (Apache-2.0, in classes.dex)",
                listOf(apacheLicense),
                intro = "These libraries are under the Apache License 2.0, below:\n\n" +
                    (libraries - mail.toSet() - onnx.toSet()).joinToString("\n") { "  $it" },
            ),
        )
        if (onnx.isNotEmpty()) {
            File(licenses, "42-onnxruntime.txt").writeText(
                licenceText(
                    "ONNX Runtime (in classes.dex and libonnxruntime.so): MIT",
                    listOf(onnxLicense),
                    intro = onnx.joinToString("\n") { "  $it" },
                ),
            )
            File(licenses, "43-u2net-model.txt").writeText(
                licenceText(
                    "U-2-Net (the subject-finding model, assets/models/u2netp.onnx): Apache-2.0",
                    listOf(apacheLicense),
                    intro = "Copyright (c) Xuebin Qin and the U-2-Net authors. https://github.com/xuebinqin/U-2-Net\n" +
                        "The model file is unmodified, in ONNX form as published with rembg (github.com/danielgatis/rembg).",
                ),
            )
        }
        if (mail.isNotEmpty()) {
            File(licenses, "41-jakarta-mail.txt").writeText(
                licenceText(
                    "Jakarta Mail for Android (in classes.dex): Eclipse Public License 2.0, or GPL-2.0 with the Classpath Exception",
                    listOf(mailLicense, mailNotice),
                    intro = mail.joinToString("\n") { "  $it" },
                ),
            )
        }

        // The Go modules linked into libgojni.so, from each ABI's build info.
        val work = temporaryDir.apply { listFiles()?.forEach { it.deleteRecursively() } }
        val linked = sortedMapOf<String, String>() // module path -> version
        val linkedReplace = sortedMapOf<String, String>() // module path -> replacement, as built
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
                var lastDep: String? = null
                for (line in info) {
                    val f = line.split('\t')
                    if (f.size >= 4 && f[1] == "dep") {
                        linked[f[2]] = f[3]
                        lastDep = f[2]
                    } else if (f.size >= 3 && f[1] == "=>" && lastDep != null) {
                        // A replaced module: "=>", then its replacement (a directory for a local copy).
                        linkedReplace[lastDep] = f[2]
                    } else {
                        lastDep = null
                    }
                }
            }
        }

        // Where each module's source is (its licence files are at its root).
        // Fields: main, path, version, source dir, replacement path ("" if none).
        fun moduleList() = go(
            modDir, "list", "-m", "-f",
            "{{.Main}}\t{{.Path}}\t{{.Version}}\t{{with .Replace}}{{.Dir}}{{else}}{{.Dir}}{{end}}\t{{with .Replace}}{{.Path}}{{end}}", "all",
        ).lines().map { it.split('\t') }.filter { it.size == 5 }.associateBy { it[1] }
        var modules = moduleList()
        // `go list -m` reports .Dir only for a module already extracted in the
        // module cache (and exits 0 with an empty one otherwise): the cache may
        // have been cleaned, or be another GOMODCACHE, since libgojni.so was
        // built. Fetch the linked modules it lacks, then look again.
        val absent = linked.filter { (path, version) ->
            modules[path]?.let { it[0] != "true" && it[2] == version && it[3].isBlank() } == true
        }
        if (absent.isNotEmpty()) {
            go(modDir, "mod", "download", *absent.map { (path, version) -> "$path@$version" }.toTypedArray())
            modules = moduleList()
        }
        for ((path, version) in linked) {
            val m = modules[path]
                ?: throw GradleException("Go module $path is in libgojni.so but not in gmbridge's module list")
            if (m[0] == "true") continue // gmbridge itself: this app's code, under its LICENSE
            if (m[2] != version) throw GradleException("Go module $path: libgojni.so has $version, go.mod ${m[2]}")
            if (m[3].isBlank()) {
                throw GradleException(
                    "Go module $path $version is not in the Go module cache (GOMODCACHE), and " +
                        "'go mod download' in gmbridge/ did not put it there",
                )
            }
            val dir = File(m[3])
            // The replacement libgojni.so was built with must be the one go.mod names now.
            val builtReplace = linkedReplace[path]
            val replaced = m[4].isNotBlank()
            if (replaced != (builtReplace != null)) {
                throw GradleException(
                    "Go module $path: libgojni.so was built " +
                        (if (builtReplace != null) "with replacement $builtReplace" else "without a replacement") +
                        ", go.mod " + (if (replaced) "replaces it with ${m[4]}" else "does not replace it"),
                )
            }
            if (builtReplace != null) {
                val builtDir = File(builtReplace).let { if (it.isAbsolute) it else File(modDir, builtReplace) }
                if (!builtDir.isDirectory || builtDir.canonicalFile != dir.canonicalFile) {
                    throw GradleException("Go module $path: libgojni.so was built with replacement $builtReplace, go.mod has $dir")
                }
            }
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            val texts = files.filter { licenceName.matches(it.name) }
            if (texts.isEmpty()) {
                throw GradleException("No licence file in $dir for Go module $path $version; add its licence before shipping it")
            }
            val patches = files.filter { patchesName.matches(it.name) }
            if (replaced && patches.isEmpty()) {
                throw GradleException("Go module $path is replaced by $dir, which has no PATCHES.md saying what was changed")
            }
            val name = "20-go-" + path.replace(Regex("[^A-Za-z0-9.-]+"), "_") + ".txt"
            val heading = if (replaced) {
                "$path $version, patched for LightDecoder (Go module, in libgojni.so; the changes are listed below)"
            } else {
                "$path $version (Go module, in libgojni.so)"
            }
            File(licenses, name).writeText(
                licenceText(heading, texts + files.filter { extraName.matches(it.name) } + patches),
            )
        }

        // SQLite itself: github.com/mattn/go-sqlite3 (the WhatsApp store's
        // driver) compiles the SQLite amalgamation it ships, sqlite3-binding.c,
        // into libgojni.so with cgo. SQLite is in the public domain; its
        // sources carry a blessing instead of a licence, quoted from the
        // amalgamation that was built, with its version.
        val sqliteModule = "github.com/mattn/go-sqlite3"
        linked[sqliteModule]?.let { driverVersion ->
            val dir = File(modules.getValue(sqliteModule)[3])
            val amalgamation = File(dir, "sqlite3-binding.c")
            if (!amalgamation.isFile) {
                throw GradleException("No sqlite3-binding.c in $dir: cannot name the SQLite linked into libgojni.so")
            }
            val source = amalgamation.readText()
            val sqliteVersion = Regex("#define SQLITE_VERSION\\s+\"([^\"]+)\"").find(source)?.groupValues?.get(1)
                ?: throw GradleException("No SQLITE_VERSION in $amalgamation")
            val blessing = Regex(
                "The author disclaims copyright to this source code\\.[\\s\\S]*?" +
                    "May you share freely, never taking more than you give\\.",
            ).find(source)?.value
                ?: throw GradleException("SQLite's public-domain notice not found in $amalgamation; check its licence")
            File(licenses, "21-sqlite.txt").writeText(
                "SQLite $sqliteVersion (C library, compiled into libgojni.so by $sqliteModule $driverVersion)\n\n" +
                    "SQLite is in the public domain (https://sqlite.org/copyright.html). " +
                    "Its source files carry this notice instead of a licence:\n\n" +
                    blessing.lines().map { it.trim().removePrefix("**").trim() }
                        .joinToString("\n") { if (it.isEmpty()) "" else "    $it" } + "\n",
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
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // The Gmail tab: IMAP to read, SMTP to send (Jakarta Mail 1.6 for Android; EPL-2.0 or GPL-2.0 + CPE).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Draws the QR code of the WhatsApp setup screen (encoding only; Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    // Runs the sticker maker's subject-finding model (assets/models/u2netp.onnx) on the phone.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // The same runtime for the computer the unit tests run on.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")

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

// Unit tests run on a computer: the phone's ONNX Runtime (same classes, Android libraries) stays out.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") || it.name.endsWith("UnitTestCompileClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
