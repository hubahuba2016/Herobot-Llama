plugins {
    id("com.android.application")
}

android {
    namespace = "com.herobot"
    compileSdk = 34
    
    buildFeatures {
        viewBinding = true
    }
    
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }
    defaultConfig {
        applicationId = "com.herobot"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val releaseStoreFile = project.findProperty("herobotStoreFile") as String?
        ?: System.getenv("HEROBOT_STORE_FILE")
        ?: "app/herobot-release.keystore"

    val releaseStorePassword = project.findProperty("herobotStorePassword") as String?
        ?: System.getenv("HEROBOT_STORE_PASSWORD")
        ?: "774954hbkckpbgt"

    val releaseKeyAlias = project.findProperty("herobotKeyAlias") as String?
        ?: System.getenv("HEROBOT_KEY_ALIAS")
        ?: "herobot"

    val releaseKeyPassword = project.findProperty("herobotKeyPassword") as String?
        ?: System.getenv("HEROBOT_KEY_PASSWORD")
        ?: "774954hbkckpbgt"

    val normalizedReleaseStoreFile = if (File(releaseStoreFile).isAbsolute) {
        File(releaseStoreFile)
    } else {
        rootProject.file(releaseStoreFile)
    }
    
    signingConfigs {
        create("release") {
            storeFile = normalizedReleaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")

    testImplementation("junit:junit:4.13.2")

    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.json:json:20240303")
}
