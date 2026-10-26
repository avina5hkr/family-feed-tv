plugins {
    alias(libs.plugins.android.application)
}

// Version: -PversionName=1.2.3 -PversionCode=10203 (the release workflow derives both from the tag).
val appVersionName = providers.gradleProperty("versionName").getOrElse("0.0.0-dev")
val appVersionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)

// Release signing comes only from environment variables (set by the release workflow). Without them the release
// APK is built unsigned, so builds work on any machine with no secrets.
val keystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val canSignRelease = listOf(keystorePath, keystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrEmpty() }

android {
    namespace = "com.avinash.facebookfeed"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.avinash.facebookfeed"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Key 9 looks for updates in this repository's GitHub Releases.
        buildConfigField("String", "UPDATE_REPO", "\"avina5hkr/family-feed-tv\"")
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release build; the launcher label comes from src/debug/res.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Key 9 in debug builds reads this fake release instead of GitHub (whose releases are the release app):
            // -PupdateTestUrl=http://<Mac>:8000/latest.json. Without it, 9 only says so.
            buildConfigField("String", "UPDATE_TEST_URL", "\"${providers.gradleProperty("updateTestUrl").getOrElse("")}\"")
        }
        release {
            optimization {
                enable = false
            }
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "UPDATE_TEST_URL", "\"\"")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}