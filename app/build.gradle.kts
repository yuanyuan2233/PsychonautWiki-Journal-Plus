plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    id("androidx.room")
    kotlin("plugin.serialization") version libs.versions.kotlin
    id("kotlin-kapt") // this needs to be on bottom
}

android {
    namespace = "com.isaakhanimann.journal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.isaakhanimann.journal"
        minSdk = 26
        targetSdk = 35
        versionCode = 71
        versionName = "13.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            fun resolveSigningParam(name: String): String? =
                project.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
                    ?: System.getenv(name)?.takeIf { it.isNotBlank() }

            val keystoreFilePath = resolveSigningParam("KEYSTORE_FILE")
            val releaseStorePassword = resolveSigningParam("KEYSTORE_PASSWORD")
            val releaseKeyAlias = resolveSigningParam("KEY_ALIAS")
            val releaseKeyPassword = resolveSigningParam("KEY_PASSWORD")

            val anySigningParamProvided = listOf(
                keystoreFilePath,
                releaseStorePassword,
                releaseKeyAlias,
                releaseKeyPassword
            ).any { it != null }

            if (anySigningParamProvided) {
                val resolvedKeystore = keystoreFilePath?.let { rootProject.file(it) }
                if (resolvedKeystore == null || !resolvedKeystore.isFile) {
                    throw org.gradle.api.GradleException(
                        "Release signing requested, but KEYSTORE_FILE is missing or does not point to an existing file."
                    )
                }
                if (releaseStorePassword == null || releaseKeyAlias == null || releaseKeyPassword == null) {
                    throw org.gradle.api.GradleException(
                        "Release signing requested, but KEYSTORE_PASSWORD, KEY_ALIAS, or KEY_PASSWORD is missing or blank."
                    )
                }
                storeFile = resolvedKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".zh.plus.debug"
            versionNameSuffix = "-zh-plus-debug"
        }
        release {
            applicationIdSuffix = ".zh.plus"
            versionNameSuffix = "-zh-plus"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            ndk.debugSymbolLevel = "FULL"
        }
    }
    androidResources {
        ignoreAssetsPatterns += listOf(
            "Substances.original.json",
            "substances_zh_dict.json"
        )
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
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive.navigation.suite.android)
    testImplementation(libs.junit)
    // 为 JVM 本地单元测试提供真实 org.json 实现，避免被 Android SDK 存根跳过 TestParse
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose)

    implementation(libs.androidx.core.splashscreen)

}

kapt {
    correctErrorTypes = true
}