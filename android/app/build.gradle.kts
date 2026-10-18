/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "it.visionottica.metaexperience"
    minSdk = 31
    targetSdk = 36
    // every CI build gets a higher version so the in-app updater can detect it
    versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
    versionName = "0.8.${System.getenv("GITHUB_RUN_NUMBER") ?: "dev"}"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "0") // "0" = Developer Mode
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "0")

    // Sofia's Claude answers go through the VisionOttica server (the API key never ships in the APK).
    // From local.properties, or CI env vars SOFIA_ENDPOINT / SOFIA_APP_TOKEN (GitHub secrets).
    val sofiaEndpoint = localProperties.getProperty("sofia_endpoint") ?: System.getenv("SOFIA_ENDPOINT") ?: ""
    val sofiaToken = localProperties.getProperty("sofia_app_token") ?: System.getenv("SOFIA_APP_TOKEN") ?: ""
    buildConfigField("String", "SOFIA_ENDPOINT", "\"$sofiaEndpoint\"")
    buildConfigField("String", "SOFIA_APP_TOKEN", "\"$sofiaToken\"")
  }

  // Stable signature for the demo builds: Android only accepts an update signed with the same key.
  signingConfigs {
    create("demo") {
      storeFile = rootDir.resolve("keystore/visionottica-demo.jks")
      storePassword = "visionottica-demo"
      keyAlias = "visionottica"
      keyPassword = "visionottica-demo"
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("demo") }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.webkit)
  // Free on-device translation for the Translator station (models downloaded once per language)
  implementation("com.google.mlkit:translate:17.0.3")
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
