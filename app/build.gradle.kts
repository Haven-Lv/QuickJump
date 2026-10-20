plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chuhe.quickjump"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chuhe.quickjump"
        // vivo 手机均为 Android 8.0+，minSdk 26 可简化图标资源
        minSdk = 26
        targetSdk = 34
        versionCode = 11314529
        versionName = "1.1314.520"
    }

    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
