plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // generates JSON encoders for @Serializable classes
    alias(libs.plugins.ksp) // annotation processor for Room (faster than kapt)
    // Reads app/google-services.json (git-ignored) and generates Firebase config resources.
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.floodsos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.floodsos"
        minSdk = 26        // Android 8.0: covers older, cheaper phones common in flood-hit areas
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true // BuildConfig.VERSION_NAME goes into field-test exports
    }
}

// Room writes a JSON snapshot of the DB schema here. Commit it: it lets us
// diff schema changes in code review and test migrations later.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    // BOM = "bill of materials": picks versions of Firebase libraries that work together.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.nearby)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
