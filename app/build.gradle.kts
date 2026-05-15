plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
}

android {
    namespace = "com.lz1bgb.camhttp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lz1bgb.camhttp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    bundle {
        language {
            enableSplit = false
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        disable += listOf("NewerVersionAvailable", "OldTargetApi", "ObsoleteSdkInt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.jsch)
    implementation(libs.commons.net)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
}

tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("app-release-unsigned.apk", "app-release.apk")
    into(rootProject.file("apk"))
    rename { "camHttp-release-1.0.apk" }
}

tasks.register<Copy>("copyDebugApk") {
    from(layout.buildDirectory.dir("outputs/apk/debug"))
    include("app-debug.apk")
    into(rootProject.file("apk"))
    rename { "camHttp-debug.apk" }
}

afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy("copyReleaseApk")
    tasks.findByName("assembleDebug")?.finalizedBy("copyDebugApk")
}
