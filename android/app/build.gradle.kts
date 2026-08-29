plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "tw.umaya.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.umaya.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_BASE_URL", "\"https://tracker.umaya.tw/api/\"")
    }

    buildTypes {
        debug {
            // A different applicationId + visible name lets a debug test build install
            // side-by-side with the already-installed app instead of overwriting it —
            // useful while a change (like the map-screen redesign) hasn't been verified yet.
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "登山健行定位追蹤（測試版）")
        }
        release {
            resValue("string", "app_name", "登山健行定位追蹤")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // GuardianActivity — the one View/WebView-based screen in an otherwise Compose app
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Map rendering — plain XYZ tile source support, so it can hit the same 魯地圖/線上地圖
    // tile endpoints the guardian web page already uses, no separate map backend needed.
    // Embedded into Compose via AndroidView (already part of androidx.compose.ui:ui).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Mapsforge — real offline vector rendering of the downloaded .map file (魯地圖), replacing
    // osmdroid's raster-tile caching for that layer. 線上地圖 stays on osmdroid (plain XYZ tiles).
    implementation("org.mapsforge:mapsforge-map-android:0.19.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.19.0")
    implementation("org.mapsforge:mapsforge-core:0.19.0")
    implementation("org.mapsforge:mapsforge-map:0.19.0")

    // Local offline queue
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background sync when network becomes available
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Backend API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
