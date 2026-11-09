plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ritim.ritimcraw"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.ritim.ritimcraw"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // One ABI only (sprint plan Global Constraints).
            abiFilters += "arm64-v8a"
        }
    }

    // NDK 30.0.16248370 is installed; the JNI bridge builds through the SDK
    // CMake. Kept commented while the CMake 4.1.2 / AGP 8.13.2 pairing is
    // verified; NativeSource guards the missing .so either way.
    // TODO(ndk): enable once the CMake/AGP pairing is verified on CI.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }
    // ndkVersion = "30.0.16248370"

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Core icons only: material-icons-extended is NOT in the offline Gradle
    // cache (BOM maps it to 1.7.0 but no artifact was ever downloaded).
    // material-icons-core 1.7.0 is cached (transitive via material3 1.3.0).
    implementation("androidx.compose.material:material-icons-core")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}