fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val debugWebhookUrl =
    providers.gradleProperty("POWERWATCH_DEFAULT_WEBHOOK_URL").orElse("").get()
val debugWebhookToken =
    providers.gradleProperty("POWERWATCH_DEFAULT_WEBHOOK_TOKEN").orElse("").get()

val releaseStoreFile = providers.environmentVariable("POWERWATCH_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("POWERWATCH_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("POWERWATCH_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("POWERWATCH_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
require(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "PowerWatch release signing is only partially configured. Set all POWERWATCH_RELEASE_* variables or none."
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kiwinokoto.powerwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kiwinokoto.powerwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField(
                "String",
                "POWERWATCH_DEFAULT_WEBHOOK_URL",
                debugWebhookUrl.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "POWERWATCH_DEFAULT_WEBHOOK_TOKEN",
                debugWebhookToken.asBuildConfigString()
            )
        }

        getByName("release") {
            buildConfigField("String", "POWERWATCH_DEFAULT_WEBHOOK_URL", "\"\"")
            buildConfigField("String", "POWERWATCH_DEFAULT_WEBHOOK_TOKEN", "\"\"")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
