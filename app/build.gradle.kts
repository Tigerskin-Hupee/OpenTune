@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "app.opentune"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.opentune"
        minSdk = 24
        targetSdk = 36
        versionCode = 71
        versionName = "0.11.0-a2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("ot_release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                (keystoreProperties["keyAlias"] as? String)?.let {
                    keyAlias = it
                }
                (keystoreProperties["keyPassword"] as? String)?.let {
                    keyPassword = it
                }
                (keystoreProperties["storePassword"] as? String)?.let {
                    storePassword = it
                }
            }
        } else {
            create("ot_release") { }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use real release key if keystore.properties is provided,
            // otherwise fall back to the debug key so CI can still produce
            // an installable APK.
            signingConfig = if (!keystoreProperties.isEmpty) {
                signingConfigs.getByName("ot_release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Single universal APK per build type — no per-ABI splitting.
    splits {
        abi {
            isEnable = false
        }
    }

    // Single product flavour — FFmpeg metadata (full) removed, core only.
    flavorDimensions.add("variant")
    productFlavors {
        create("core") {
            isDefault = true
            dimension = "variant"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName =
                    "OpenTune-${variant.versionName}-${variant.buildType.name}.apk"
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    // Exclude FFmpeg-full scanner; always use the stub (core behaviour).
    tasks.withType<KotlinCompile> {
        exclude("**/*FFmpegScanner.kt")
        exclude("**/*NextRendersFactory.kt")
    }

    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            excludeFields = listOf("generated")
        }

        license {
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            allowedLicenses.addAll(
                "Apache-2.0",
                "BSD-3-Clause",
                "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1",
                "GPL-3.0-only",
                "GPL-3.0 license",
                "GNU GENERAL PUBLIC LICENSE, Version 3",
                "EPL-2.0",
                "MIT",
                "MPL-2.0",
                "Public Domain",
            )
            additionalLicenses.addAll("apache_2_0", "gpl_2_1")
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // material
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)

    // viewmodel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.media3)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    implementation(libs.workmanager)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.okhttp)

    // NewPipeExtractor — pure-JVM YouTube extraction. Handles PoToken,
    // signature ciphers, n-parameter throttling.
    implementation(libs.newpipe.extractor)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.aboutlibraries.compose.m3)

    implementation(project(":material-color-utilities"))
}
