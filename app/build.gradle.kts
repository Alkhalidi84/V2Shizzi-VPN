plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = providers.environmentVariable("V2SHIZZI_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("V2SHIZZI_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("V2SHIZZI_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("V2SHIZZI_KEY_PASSWORD").orNull
val hasPrivateReleaseKey = listOf(
    releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.fastnode.app"
    compileSdk = 34

    defaultConfig {
        // Keep the existing application ID so installed users can update in place.
        applicationId = "com.fastnode.app"
        minSdk = 29
        targetSdk = 33
        versionCode = 14
        versionName = "1.3.0"
    }

    signingConfigs {
        if (hasPrivateReleaseKey) {
            create("privateRelease") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release builds must never silently fall back to the debug certificate.
            if (hasPrivateReleaseKey) {
                signingConfig = signingConfigs.getByName("privateRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core:1.13.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
