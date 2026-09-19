plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * llama.cpp for the phone's own model: the answer when the Mac is out of reach.
 *
 * The native part is built from the submodule at `third_party/llama.cpp`, pinned to tag
 * b11053 (commit 1af554f8fc78ba029665a47b839484d9763e2a75), with the flags M5's design
 * settled on: shared libraries, the CPU backend built once per ARM generation and chosen
 * when the app starts (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`), Arm's KleidiAI
 * kernels, and nothing that reaches a network or spawns a process at run time.
 */
val llamaSource: File = rootDir.resolve("../third_party/llama.cpp")

android {
    namespace = "dev.siliconoptimizer.buddy.llama"
    compileSdk = 35
    // The NDK that measured the models on the owner's phone. r28 and later align native
    // libraries to 16 KB pages by default, which Android 15+ devices may require.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64 only. The S24 Ultra is arm64, and llama.cpp's fast paths — dot
            // products, int8 matrix multiply, KleidiAI — are all AArch64. A build for any
            // other ABI simply has no native library in it, and the app says the on-device
            // model is not available on that phone.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // Release optimisation in every variant: a debug build of the app with an
                // -O0 llama.cpp would take a minute a sentence.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Eleven shared libraries share one C++ runtime rather than eleven copies
                // of it, so a type or an exception that crosses between them is the same
                // type on both sides.
                arguments += "-DANDROID_STL=c++_shared"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_CPU_KLEIDIAI=ON"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_CCACHE=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_SUBPROCESS=OFF"

                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_UI=OFF"

                arguments += "-DBUDDY_LLAMA_SOURCE=${llamaSource.absolutePath}"
                // KleidiAI's sources are fetched by llama.cpp's own CMake, pinned there by
                // version and checksum. An offline machine can point at an unpacked copy.
                (findProperty("buddy.kleidiaiSource") as String?)?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=$it"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            // The SDK's own CMake; llama.cpp b11053 asks for 3.14 and KleidiAI for 3.16.
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// A clone without `--recursive` has an empty folder where llama.cpp should be, and CMake's
// own error about it is three screens long. This one is a sentence.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    doFirst {
        if (!llamaSource.resolve("CMakeLists.txt").exists()) {
            throw GradleException(
                "llama.cpp is not checked out at ${llamaSource.path}. " +
                    "Run: git submodule update --init --depth 1 third_party/llama.cpp",
            )
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
