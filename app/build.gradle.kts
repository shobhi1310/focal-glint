plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

// Extract libLiteRt.so from the litertlm AAR so CMake can link against it at build time.
// The extracted file is in build/ and is NOT packaged into the APK — litertlm-android
// already packages the real libLiteRt.so. This is purely a link-time stub.
val stageLiteRtSo by tasks.registering {
    val litertlmVersion = "0.11.0"
    val outDir = layout.buildDirectory.dir("litert_link/arm64-v8a")
    outputs.dir(outDir)
    doLast {
        val aar = configurations.detachedConfiguration(
            dependencies.create("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion@aar")
        ).resolve().single()
        val dest = outDir.get().asFile.also { it.mkdirs() }
        project.zipTree(aar)
            .matching { include("jni/arm64-v8a/libLiteRt.so") }
            .singleFile
            .copyTo(File(dest, "libLiteRt.so"), overwrite = true)
        logger.lifecycle("Staged libLiteRt.so from litertlm-android:$litertlmVersion")
    }
}

tasks.named("preBuild") { dependsOn(stageLiteRtSo) }

android {
    namespace = "com.focal"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.focal"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DLITERT_HEADERS_DIR=${file("../../LiteRT").absolutePath}",
                    "-DLITERT_LIB_DIR=${layout.buildDirectory.get().asFile.absolutePath}/litert_link"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            // libLiteRt.so is now provided solely by litertlm-android; no conflict.
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // LiteRT-LM for on-device LLM inference (also supplies libLiteRt.so for embeddings)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.11")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
