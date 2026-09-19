plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.siliconoptimizer.buddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.siliconoptimizer.buddy"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ML Kit's barcode scanner — the QR reader on the pairing screen — bundles
            // `libbarhopper_v3.so`, and it arrives for four ABIs: 19 MB, of which 11.6 MB
            // is x86 and x86_64 and 3.1 MB is 32-bit ARM. The S24 Ultra is arm64, minSdk
            // is 29, and Play has required 64-bit since 2019, so there is no device this
            // app can be installed on that needs any of the other three.
            //
            // This applies to *every* variant, debug included, so an x86_64 emulator
            // cannot install the default build. That is deliberate — the Mac here is
            // Apple Silicon and its emulators are arm64 — and `-Pbuddy.abis=x86_64` is
            // the way back for an Intel emulator or a CI runner.
            abiFilters += (findProperty("buddy.abis") as String? ?: "arm64-v8a")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
    }

    signingConfigs {
        // Release is signed with the local debug key when there is one, purely so it can
        // be installed and instrumented. It is not a distribution key and must not become
        // one: anybody's debug keystore has the same password. A real key belongs in the
        // owner's keychain and in CI secrets, and until then `assembleRelease` on a
        // machine without `~/.android/debug.keystore` produces an unsigned APK — see
        // docs/PLAN.md.
        create("local") {
            val keystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Most of this APK is code nothing calls: `material-icons-extended` alone
            // puts about ten thousand icon classes in the dex for the two dozen this app
            // draws. Unshrunk, release came out at 75 MB. See `proguard-rules.pro` for
            // the handful of things that have to survive R8 — the wire types especially,
            // which are reached only by name through kotlinx.serialization.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-androidTest.pro")
            signingConfigs.getByName("local").storeFile?.let { signingConfig = signingConfigs.getByName("local") }
        }
        debug {
            // The control API is plain HTTP on a tailnet address; see
            // res/xml/network_security_config.xml for the exception this needs.
            isMinifyEnabled = false
        }
    }

    // Instrumented tests run against the *minified* build, because the thing worth
    // testing on a device is whether R8's keep rules survived. A debug APK keeps
    // everything and would prove nothing.
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // The Settings screen shows the app's own version.
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        // The contract fixtures live at the repository root and are shared with the iOS
        // app. Reading them straight from there keeps one copy of the truth.
        getByName("test").resources.srcDir("../../contract")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Glance is the only way to draw an app widget in Compose; a RemoteViews widget
    // would mean a second UI toolkit in this app for one screen's worth of content.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
