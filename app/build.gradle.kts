plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.fp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.play.services.maps)
    implementation(libs.vision.common)
    implementation(libs.play.services.mlkit.face.detection)

    implementation(libs.camera.core)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.litert.api)
    implementation(libs.litert)
    implementation(libs.litert.support.api)
    implementation(libs.firebase.crashlytics.buildtools)
    testImplementation(libs.junit)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle.v110)
    implementation(libs.androidx.camera.view.v110)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

