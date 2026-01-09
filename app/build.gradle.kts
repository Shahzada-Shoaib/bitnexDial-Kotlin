import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("kotlin-parcelize")
}

// Load version from properties file
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        load(FileInputStream(versionPropsFile))
    }
}
val appVersionName: String = versionProps.getProperty("VERSION_NAME", "1.0.0")
val appVersionCode: Int = versionProps.getProperty("VERSION_CODE", "1").toInt()

// Load signing config from local.properties (for release builds)
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        load(FileInputStream(localPropsFile))
    }
}

android {
    namespace = "com.bitnextechnologies.bitnexdial"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bitnextechnologies.bitnexdial"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // Product flavors for different server environments
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            // Development server configuration
           


            buildConfigField("String", "API_BASE_URL", "\"https://bkpmanual.bitnexdial.com\"")
    buildConfigField("String", "SOCKET_URL", "\"https://bkpmanual.bitnexdial.com:3000\"")
    buildConfigField("String", "SIP_DOMAIN", "\"bkpmanual.bitnexdial.com\"")
    buildConfigField("String", "WSS_SERVER", "\"bkpmanual.bitnexdial.com\"")
    buildConfigField("String", "WSS_PORT", "\"8089\"")
    buildConfigField("String", "WSS_PATH", "\"/ws\"")
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"

            // Staging server configuration
            buildConfigField("String", "API_BASE_URL", "\"https://bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "SOCKET_URL", "\"https://bkpmanual.bitnexdial.com:3000\"")
            buildConfigField("String", "SIP_DOMAIN", "\"bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "WSS_SERVER", "\"bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "WSS_PORT", "\"8089\"")
            buildConfigField("String", "WSS_PATH", "\"/ws\"")
        }

        create("prod") {
            dimension = "environment"
            // No suffix for production - uses base applicationId

            // Production server configuration (using bkpmanual server)
            buildConfigField("String", "API_BASE_URL", "\"https://bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "SOCKET_URL", "\"https://bkpmanual.bitnexdial.com:3000\"")
            buildConfigField("String", "SIP_DOMAIN", "\"bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "WSS_SERVER", "\"bkpmanual.bitnexdial.com\"")
            buildConfigField("String", "WSS_PORT", "\"8089\"")
            buildConfigField("String", "WSS_PATH", "\"/ws\"")
        }
    }

    signingConfigs {
        create("release") {
            // Load signing config from local.properties or environment variables
            // For CI/CD, use environment variables: RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, etc.
            // For local development, add to local.properties (never commit this file!)

            val keystorePath = localProps.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
            val keystorePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val releaseKeyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val releaseKeyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null &&
                releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            // Removed applicationIdSuffix to match google-services.json package name
            // applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing if configured, otherwise fall back to debug for testing
            // IMPORTANT: For production releases, configure signing in local.properties:
            //   RELEASE_STORE_FILE=path/to/keystore.jks
            //   RELEASE_STORE_PASSWORD=your_store_password
            //   RELEASE_KEY_ALIAS=your_key_alias
            //   RELEASE_KEY_PASSWORD=your_key_password
            val releaseSigningConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseSigningConfig?.storeFile != null) {
                releaseSigningConfig
            } else {
                // Fall back to debug signing if release not configured
                // WARNING: Debug-signed APKs should NOT be uploaded to Play Store
                signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Dependency Injection - Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")

    // Networking - Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Socket.IO for real-time messaging
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // Database - Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Image loading - exclude old accompanist to avoid Compose version conflict
    implementation("io.coil-kt:coil-compose:2.6.0") {
        exclude(group = "com.google.accompanist", module = "accompanist-drawablepainter")
    }

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Permissions handling - must match Compose BOM version
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // libphonenumber for phone number parsing
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.27")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric Authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ShortcutBadger for app icon badges on various launchers
    implementation("me.leolin:ShortcutBadger:1.1.22@aar")

    // Media3 ExoPlayer for fast audio streaming
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

kapt {
    correctErrorTypes = true
}
