import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val forkVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "dev.kb896.aiusage"
    compileSdk = 36

    // buildConfig: BuildConfig.DEBUG gates the diagnostics section — see MainActivity.
    buildFeatures { aidl = true; buildConfig = true; compose = true }

    defaultConfig {
        // A fork with its own identity (Chicken Rear), beside nothing: the namespace above stays the
        // original's, as the debug build's always has. SHORT ON PURPOSE: SurfaceFlinger cuts a layer's
        // name after 24 characters on Android 16, and the shell tells its own panel by "<package>/"
        // at the start of one (OwnPanel) — "dev.chicken.rear.dev/" is 21.
        applicationId = "dev.chicken.rear"
        minSdk = 34
        targetSdk = 36
        // Version comes from the release tag when CI is building one, so a published APK
        // cannot go on claiming an old number while the tags move past it. Locally it
        // stays <version>-dev, which is a useful thing to see in an installed build.
        //
        // This is the ONLY place a version is written down. Everything that needs to say
        // what it is — the User-Agent this app sends, and anything added later — reads
        // BuildConfig.VERSION_NAME rather than repeating the digits. Four User-Agent
        // headers were still introducing themselves as 0.1 long after this line had
        // moved on, which is the drift the comment above was already warning about.
        //
        // versionCode must increase for every update Android will accept, and a tag name
        // does not give a number to derive one from. CI_PIPELINE_IID is monotonic per
        // project, which is exactly the guarantee needed and nothing more.
        // The fork's own numbers, in version.properties at the root: no CI writes them.
        versionCode = forkVersion.getProperty("code").toInt()
        versionName = forkVersion.getProperty("name")
        ndk { abiFilters += "arm64-v8a"; if (project.hasProperty("withX86")) abiFilters += "x86_64" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Chicken Rear"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /**
     * Release signing, from the environment — never from a file in this repository.
     *
     * This used to be `signingConfigs.getByName("debug")`, which is fine for a build you
     * install on your own phone over a cable and actively wrong for one you hand to
     * anyone else: the debug key is not a secret, so anyone can forge an update for it.
     *
     * Absent credentials produce an UNSIGNED release rather than a debug-signed one. An
     * unsigned APK refuses to install and says so; a debug-signed one installs happily
     * and is the thing we are trying not to publish.
     */
    // The fork's key is never in git. keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
    // is read from where KEYSTORE_PROPERTIES points, else from the repository's root (.gitignore keeps it
    // out); a store named in it is looked for beside that file. The environment still wins.
    val keyPropsFile = System.getenv("KEYSTORE_PROPERTIES")?.let { file(it) } ?: rootProject.file("keystore.properties")
    val keyProps = Properties().apply {
        if (keyPropsFile.exists()) keyPropsFile.inputStream().use { load(it) }
    }
    val releaseStore = System.getenv("KEYSTORE_PATH") ?: keyProps.getProperty("storeFile")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = keyPropsFile.absoluteFile.parentFile.resolve(releaseStore).takeIf { it.exists() } ?: file(releaseStore)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: keyProps.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: keyProps.getProperty("keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD") ?: keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Chicken Rear"
        }
        release {
            isMinifyEnabled = true
            // Resources nothing refers to are left out (R8 tells it what code is kept). The one name looked up at
            // run time, status_bar_height, is the system's, not ours.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Explicitly NOT debuggable, and explicitly not the debug key. The token
            // store is only as private as the app's uid: `run-as` on a debuggable build
            // runs AS that uid, so it can read the encrypted tokens and use the very
            // Keystore key that encrypted them.
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
    // The lifecycle library's LiveData check crashes lint itself on this Kotlin (an
    // IncompatibleClassChangeError inside the detector, reading Panel.kt) and failed the
    // release build. The app has no LiveData for it to check.
    lint {
        disable += "NullSafeMutableLiveData"
        // lintVitalAnalyzeRelease took about 7 minutes of CPU per release build, beside R8. Lint is run on its own
        // when wanted (./gradlew :app:lintDebug); a release build only builds.
        checkReleaseBuilds = false
    }
}

// An opt-in native benchmark must not reuse the normal run's skipped JUnit result.
tasks.withType<Test>().configureEach {
    val benchmark = providers.environmentVariable("PADDLE_OCR_BENCH")
    inputs.property("paddleBenchmarkDirectory", benchmark.orElse(""))
    outputs.upToDateWhen { !benchmark.isPresent }
    outputs.doNotCacheIf("Measures the local machine") { benchmark.isPresent }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    if (project.hasProperty("withX86")) {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    } else {
        implementation(files("libs/onnxruntime-paddle-arm64-1.22.0.aar"))
    }
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    // lifts Android's hidden-API filter for this process only, so the MIUI window flag the
    // rear screen's double tap needs can be read and set (see RearActivity.takeOverDoubleTap)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.core:core-ktx:1.15.0")
    // ComponentActivity + lifecycleScope — MainActivity collects a StateFlow (Task 17).
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // The settings' bottom bar: Kyant's liquid-glass tabs (LiquidBottomTabs, ported from
    // the Backdrop catalog) on the Backdrop library, in a ComposeView. 1.0.x is the
    // Android line; 2.x is Compose Multiplatform.
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("io.github.kyant0:backdrop:1.0.6")
    // The video background's clip is cut once, on the phone, by Media3's Transformer: trim,
    // crop, scale to the rear display, no audio. See ui/video/ClipExport.
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    // Pin to location (place/): Play services' geofences and fused position. No key, no account.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // The prompt's map (ui/place/PlaceMap): MapLibre Native (BSD) on OpenFreeMap's keyless tiles. The OpenGL build, whose
    // TextureView mode is a view in our window (clipped round, not under the window's blur). Its native library ships for
    // arm64 only here (abiFilters above); -PwithX86 adds the x86_64 one for the emulator.
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    // The camera page on the back (ui/camera): CameraX on the back cameras. Extensions only to ask whether the phone
    // offers a portrait (bokeh) mode; without it there is no "Portrait".
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-extensions:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // RingGeometryTest needs a real android.os.Bundle (put/get/containsKey) — the plain
    // JVM mockable-android.jar throws "not mocked" for it, same trap as org.json (see
    // UsageParser.kt). Scoped to that one test class via @RunWith(RobolectricTestRunner).
    testImplementation("org.robolectric:robolectric:4.13")
    // ApplicationProvider, which is how every Robolectric test here gets the Context the
    // glass views need in their constructors — Robolectric alone does not supply one.
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
