import java.util.Properties

// Application module (not a library). The engine module holds the Chaquopy
// runtime and the V0 parity harness; V7 adds the Compose UI on top. Chaquopy is
// designed and tested against application modules, and the app-module task graph
// is its well-trodden path.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// --------------------------------------------------------------------------- //
// Release signing material
// --------------------------------------------------------------------------- //
/**
 * Where the upload key comes from — never from this repository.
 *
 * `keystore.properties` at the repo root (gitignored) or the matching
 * SIMOSCAL_* environment variables. A keystore committed alongside the source
 * it signs is not a signing key, it is a shared secret, and Play App Signing
 * treats a leaked upload key as a key that must be reset.
 *
 * All-or-nothing on purpose: a partially configured keystore throws at
 * configuration time rather than quietly falling back to an unsigned build.
 * This build's whole idiom is enforcing claims with a task instead of a comment
 * (see the permission gate below), and "signed" is a claim.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}

fun signingSecret(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))
        ?.takeIf { value -> value.isNotBlank() }

val releaseSigningMaterial: Map<String, String>? =
    signingSecret("storeFile", "SIMOSCAL_STORE_FILE")?.let { storePath ->
        val material = mapOf(
            "storeFile" to storePath,
            "storePassword" to signingSecret("storePassword", "SIMOSCAL_STORE_PASSWORD"),
            "keyAlias" to signingSecret("keyAlias", "SIMOSCAL_KEY_ALIAS"),
            "keyPassword" to signingSecret("keyPassword", "SIMOSCAL_KEY_PASSWORD"),
        )
        val missing = material.filterValues { value -> value == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is half-configured: a store file was given but " +
                    missing.joinToString(", ") + " " +
                    (if (missing.size == 1) "is" else "are") + " missing.\n" +
                    "Set every field in keystore.properties or the SIMOSCAL_STORE_FILE / " +
                    "SIMOSCAL_STORE_PASSWORD / SIMOSCAL_KEY_ALIAS / SIMOSCAL_KEY_PASSWORD " +
                    "environment variables."
            )
        }
        material.mapValues { entry -> entry.value!! }
    }

android {
    namespace = "com.simoscal.engine"
    // compileSdk 33 pairs with AGP 7.4.2 (see the root build for why the tooling
    // is pinned pre-8.0). It only governs the build; the runtime under test is
    // Python 3.13 + numpy, fixed by the Chaquopy version. minSdk 26 unchanged.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simoscal.engine"
        minSdk = 26
        targetSdk = 35
        // versionCode must increase with every Play upload and never repeat.
        // versionName was "0.0-v0" — the V0 parity gate it named was passed long
        // ago, and the app has had a full Compose UI since V7.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a only, deliberately.
            //
            // This build used to also ship x86_64, which made the APK claim an
            // architecture whose numerics had never been checked. V0's whole
            // point is that the embedded engine computes byte-identical results
            // to the desktop — proven at digest 9e6ee056… on desktop, an arm64
            // emulator, and a physical Galaxy Tab A9+. x86_64 was never proven,
            // and could not be proven on the development machine: the
            // Apple-silicon emulator ships qemu-system-aarch64 and
            // qemu-system-armel and no x86_64 backend, so an x86_64 image has
            // nothing to boot on there whatever is installed.
            //
            // Faced with prove-it-or-drop-it, the honest close is to stop making
            // the claim. The target device is arm64, so nothing anyone runs is
            // lost. Re-adding "x86_64" here is a one-line change, but it re-opens
            // the parity question and must not be done without running the leg on
            // a real x86_64 host.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Declared only when the material is actually present, so a checkout
        // without a keystore still configures, builds debug, and runs `check`.
        releaseSigningMaterial?.let { material ->
            create("release") {
                storeFile = file(material.getValue("storeFile"))
                storePassword = material.getValue("storePassword")
                keyAlias = material.getValue("keyAlias")
                keyPassword = material.getValue("keyPassword")
            }
        }
    }

    // Instrumented tests run against `debug` by default, which has R8 off — so
    // the on-device suite proves nothing about the release build. Pass
    // -PtestReleaseBuild to point `connectedAndroidTest` at the minified variant
    // instead; that is the only way to catch a missing Chaquopy keep, which fails
    // at runtime while the build stays green. Needs release signing material.
    testBuildType = if (project.hasProperty("testReleaseBuild")) "release" else "debug"

    buildTypes {
        getByName("release") {
            // R8 shrinking is ON, and `engine/proguard-rules.pro` is load-bearing:
            // Chaquopy ships a plain JAR with no consumer rules and reaches its
            // classes from C over JNI, so without those keeps the interpreter
            // fails to start on a device while the build stays green. Read that
            // file before changing anything here.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Test-only app-side keeps, added ONLY when -PtestReleaseBuild points
            // the instrumented suite here. Gated so a shipping release build is
            // never widened to satisfy the test runner. See the file's header.
            if (project.hasProperty("testReleaseBuild")) {
                proguardFile("proguard-rules-releasetest.pro")
            }
            // Applies to the androidTest APK when -PtestReleaseBuild points the
            // instrumented suite at this variant. Kept out of proguardFiles so
            // test-only relaxations can never reach the shipped app.
            testProguardFiles("proguard-rules-androidTest.pro")
            signingConfig = signingConfigs.findByName("release")
        }

        getByName("debug") {
            // No applicationIdSuffix. The FileProvider authority is derived from
            // ${applicationId}, and a suffix would also install debug as a second
            // package — orphaning the session, imported files and recovery state
            // already on the tablet. Side-by-side installs are not worth that here.
            isMinifyEnabled = false
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
        compose = true
    }

    composeOptions {
        // 1.5.14 is the Compose compiler built for Kotlin 1.9.24 (pinned in the
        // root build). These two move together; changing one alone fails the build.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            // The JVM unit tests exercise pure logic (state rules, envelope
            // parsing). They must not silently no-op against the stub android.jar,
            // so defaults stay OFF and anything Android-shaped is a real failure.
            isReturnDefaultValues = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*")
    }
}

chaquopy {
    defaultConfig {
        // Pinned to the Chaquopy target the V0 measurements were taken against.
        version = "3.13"

        // Chaquopy needs a host interpreter of the same minor version to resolve
        // and build pure-Python packages. Homebrew's python@3.13 is what the
        // host golden is generated with, so both halves of the parity comparison
        // come from the same minor version.
        //
        // Overridable because that path exists only on an Apple-silicon Mac with
        // Homebrew, and the x86_64 parity leg has to build on a Linux runner. The
        // default is unchanged, so a local build behaves exactly as before; CI
        // sets SIMOSCAL_BUILD_PYTHON to its own 3.13. Whatever is chosen must
        // still be 3.13 — a different minor version would silently resolve
        // different pure-Python packages into the APK.
        buildPython(
            (project.findProperty("simoscal.buildPython") as String?)
                ?: System.getenv("SIMOSCAL_BUILD_PYTHON")
                ?: "/opt/homebrew/opt/python@3.13/bin/python3.13"
        )

        pip {
            // Pin the exact Android NumPy runtime exercised by the V0 parity
            // gate. The library's desktop requirement stays broad, but the
            // embedded safety kernel must not change under an unchanged APK
            // build merely because a newer compatible wheel was published.
            install("numpy==1.26.2")

            // Installs simoscal from a working tree, so the device runs a
            // *checkout* rather than a published copy. Core deps are numpy-only
            // by design (see simoscal's pyproject.toml), which is what keeps
            // matplotlib and openpyxl out of the mobile closure entirely.
            //
            // simoscal lives in its own repo (SamRyeIn/simoscal) since the
            // 2026-08-18 split; the default assumes it is checked out alongside
            // this one. Override with -Psimoscal.dir=... or SIMOSCAL_DIR.
            install(
                (project.findProperty("simoscal.dir") as String?)
                    ?: System.getenv("SIMOSCAL_DIR")
                    ?: "../../simoscal"
            )
        }
    }

    // No explicit Python sourceSet: `src/main/python` is already Chaquopy's default
    // Python source directory, so the parity payload placed there is picked up
    // automatically.
}

dependencies {
    // Every AndroidX version below is the newest that still compiles against
    // SDK 33. Anything newer requires compileSdk 34+, which would drag in AGP 8
    // and break the Chaquopy task graph (see the root build's comment).
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.compose.ui:ui:1.4.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.4.3")
    implementation("androidx.compose.material3:material3:1.1.1")
    // Core icons only. `material-icons-extended` carries every Material icon and
    // measured 5.4 MB of APK (71.2 → 65.8 MB) for the three glyphs the navigation
    // bar uses — a bad trade on a build already carrying Python and numpy.
    implementation("androidx.compose.material:material-icons-core:1.4.3")
    implementation("androidx.navigation:navigation-compose:2.6.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling:1.4.3")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, because the unit-test android.jar ships only stubs that
    // throw. The bridge envelope is parsed with org.json in production, so the
    // tests must exercise the same implementation, not a mock of it.
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // AndroidX test artifacts pinned to versions that compile against SDK 33.
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

// --------------------------------------------------------------------------- //
// Manifest permission gate
// --------------------------------------------------------------------------- //
/**
 * Fails the build if the *merged* manifest declares any permission.
 *
 * The app never touches the network and never talks to the vehicle: it edits
 * a file the person picked and hands the result to SimosTools through the share
 * sheet. A permission-free manifest is how that claim is enforced rather than
 * merely documented — and it is checked on the merged manifest, so a permission
 * contributed by a *library* fails it too.
 */
abstract class VerifyNoPermissionsTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    /**
     * The one tolerated entry, and why.
     *
     * `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is *defined by the app for
     * itself* by AndroidX Core (it guards `ContextCompat.registerReceiver`
     * against other apps delivering broadcasts to a non-exported receiver). It
     * is signature-level and scoped to this application id, so it grants no
     * access to the network, storage, location, or the vehicle — the things the
     * v1 constraint is actually about. It is allowed by exact name rather than
     * by pattern so that a real permission cannot slip past alongside it.
     */
    private fun allowed(): Set<String> = setOf(
        "${applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { match -> match.groupValues[1] }
            .toSortedSet()

        val unexpected = declared - allowed()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "The app's merged manifest must declare no permissions, but found:\n" +
                    unexpected.joinToString("\n") { name -> "  - $name" } +
                    "\nIf a permission is genuinely required, that is a design decision " +
                    "for the plan's V7 constraints — not something to add here quietly."
            )
        }
        receipt.get().asFile.writeText(
            buildString {
                appendLine("merged manifest: ${manifest.name}")
                appendLine("unexpected permissions: none")
                declared.forEach { name -> appendLine("allowed: $name") }
            }
        )
    }
}

androidComponents {
    onVariants { variant ->
        // Capitalised by hand rather than with `replaceFirstChar`: Gradle compiles
        // `.kts` build scripts against Kotlin apiVersion 1.4, so stdlib functions
        // added later are simply not on the script's classpath.
        val variantName = variant.name
        val titled = variantName.substring(0, 1).toUpperCase() + variantName.substring(1)
        val verifyTask = tasks.register<VerifyNoPermissionsTask>("verify${titled}NoPermissions") {
            group = "verification"
            description = "Fails if the merged manifest declares any permission."
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            applicationId.set(variant.applicationId)
            receipt.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        // Wired into `check` so it runs with the ordinary verification tasks
        // rather than only when someone remembers to invoke it.
        tasks.named("check").configure { dependsOn(verifyTask) }
    }
}

// --------------------------------------------------------------------------- //
// Release signing gate
// --------------------------------------------------------------------------- //
/**
 * Fails a release assemble/bundle that has no signing material.
 *
 * AGP's default is to emit an *unsigned* release artifact and carry on, which is
 * the quiet failure this project does not accept anywhere else: the build looks
 * successful and the problem only surfaces at upload, or worse, as an APK that
 * cannot be installed by the person meant to test it. The check is scoped to the
 * release packaging tasks, so `check`, `assembleDebug` and a fresh clone with no
 * keystore all behave exactly as before.
 */
tasks.matching { task -> task.name == "assembleRelease" || task.name == "bundleRelease" }
    .configureEach {
        doFirst {
            if (releaseSigningMaterial == null) {
                throw GradleException(
                    "Refusing to build an unsigned release artifact.\n" +
                        "Create an upload key and point the build at it:\n" +
                        "  keytool -genkeypair -v -keystore <path outside this repo>/simoscal-upload.jks \\\n" +
                        "    -alias simoscal-upload -keyalg RSA -keysize 4096 -validity 10000\n" +
                        "then put storeFile/storePassword/keyAlias/keyPassword in keystore.properties " +
                        "at the repo root (gitignored), or set SIMOSCAL_STORE_FILE, " +
                        "SIMOSCAL_STORE_PASSWORD, SIMOSCAL_KEY_ALIAS and SIMOSCAL_KEY_PASSWORD.\n" +
                        "Keep the keystore and its passwords out of this repository."
                )
            }
        }
    }
