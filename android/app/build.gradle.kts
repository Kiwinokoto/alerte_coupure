fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val debugWebhookUrl =
    providers.gradleProperty("POWERWATCH_DEFAULT_WEBHOOK_URL").orElse("").get()
val debugWebhookToken =
    providers.gradleProperty("POWERWATCH_DEFAULT_WEBHOOK_TOKEN").orElse("").get()

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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
