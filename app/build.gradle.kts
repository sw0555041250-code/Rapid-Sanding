plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val hasKey = file("quickreceive.p12").exists()

android {
    namespace = "com.chdv.quickreceive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chdv.quickreceive"
        minSdk = 29
        targetSdk = 30
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (hasKey) {
            create("fixed") {
                storeFile = file("quickreceive.p12")
                storeType = "pkcs12"
                storePassword = "quickreceive123"
                keyAlias = "quickreceive"
                keyPassword = "quickreceive123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
