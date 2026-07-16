plugins {
    alias(libs.plugins.android.application)
}

fun gitOutput(vararg args: String): String {
    val bundledGit = file("C:/Program Files/Git/cmd/git.exe")
    val command = listOf(if (bundledGit.exists()) bundledGit.absolutePath else "git") + args
    return providers.exec {
        workingDir(rootDir)
        commandLine(command)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val gitBranch = gitOutput("rev-parse", "--abbrev-ref", "HEAD").ifBlank { "unknown" }
val gitCommitCount = gitOutput("rev-list", "--count", "HEAD").ifBlank { "0" }
val gitShortSha = gitOutput("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
val gitDirtySuffix = if (gitOutput("status", "--porcelain").isBlank()) "" else "-dirty"

android {
    namespace = "com.example.sdrvideoscanner"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.sdrvideoscanner"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_BRANCH", buildConfigString(gitBranch))
        buildConfigField("String", "BUILD_NUMBER", buildConfigString("$gitCommitCount$gitDirtySuffix"))
        buildConfigField("String", "GIT_SHA", buildConfigString(gitShortSha))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DSDRVIDEOSCANNER_REQUIRE_LIBIIO=ON",
                    "-DSDRVIDEOSCANNER_LIBIIO_INCLUDE_DIR=${projectDir}/src/main/cpp/third_party/libiio/include",
                    "-DSDRVIDEOSCANNER_LIBIIO_LIBRARY=${projectDir}/src/main/jniLibs/arm64-v8a/libiio.so",
                )
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
