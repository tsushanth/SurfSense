plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kreativekoala.surfsense"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kreativekoala.surfsense"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "10.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storeFile = providers.gradleProperty("ANDROID_UPLOAD_STORE_FILE")
        val storePassword = providers.gradleProperty("ANDROID_UPLOAD_STORE_PASSWORD")
        val keyAlias = providers.gradleProperty("ANDROID_UPLOAD_KEY_ALIAS")
        val keyPassword = providers.gradleProperty("ANDROID_UPLOAD_KEY_PASSWORD")
        val signingProps = listOf(storeFile, storePassword, keyAlias, keyPassword)
        if (signingProps.any { it.isPresent } && !signingProps.all { it.isPresent }) {
            logger.warn("Release signing disabled: set all of ANDROID_UPLOAD_STORE_FILE, ANDROID_UPLOAD_STORE_PASSWORD, ANDROID_UPLOAD_KEY_ALIAS, ANDROID_UPLOAD_KEY_PASSWORD")
        }
        if (signingProps.all { it.isPresent }) {
            create("release") {
                this.storeFile = file(storeFile.get())
                this.storePassword = storePassword.get()
                this.keyAlias = keyAlias.get()
                this.keyPassword = keyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            providers.gradleProperty("APP_ID_SUFFIX").orNull?.let { applicationIdSuffix = it }
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(libs.androidx.recyclerview)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material Design
    implementation(libs.material.android)
    implementation("androidx.compose.material:material-icons-extended")

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Security
    implementation(libs.security.crypto)

    // Logging
    implementation(libs.timber)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
