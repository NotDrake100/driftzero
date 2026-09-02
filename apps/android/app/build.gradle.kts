plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val learnedImuJson = rootProject.file("models/learned_imu_v1/linear_dp.json")
val motionStudentJson = rootProject.file("models/motion_student_v1/linear.json")
// linear_dp.json is worse than freeze. Opt in with -Pdriftzero.packLearnedImu=true.
// gru.json is never packed. There is no Kotlin GRU runtime.
val packLearnedImuEnabled =
    (findProperty("driftzero.packLearnedImu") as String?)?.equals("true", ignoreCase = true) == true
val packLearnedImu = tasks.register<Copy>("packLearnedImu") {
    onlyIf { packLearnedImuEnabled && learnedImuJson.isFile }
    from(learnedImuJson)
    into(layout.buildDirectory.dir("generated/learnedImuAssets/learned_imu_v1"))
}
val packMotionStudent = tasks.register<Copy>("packMotionStudent") {
    onlyIf { motionStudentJson.isFile }
    from(motionStudentJson)
    into(layout.buildDirectory.dir("generated/learnedImuAssets/motion_student_v1"))
}

android {
    namespace = "in.driftzero.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.driftzero.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/learnedImuAssets"))

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild").configure {
    dependsOn(packLearnedImu, packMotionStudent)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":navigation-core"))
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
