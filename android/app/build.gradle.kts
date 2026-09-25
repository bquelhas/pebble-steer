plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bquelhas.steer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bquelhas.steer"
        minSdk = 24
        targetSdk = 36
        versionCode = 18
        versionName = "1.5.12"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

// Bundle the companion watchapp (.pbw) into the APK's assets so the Developer screen can
// hand it to the Pebble / Core Devices app for install ("o pbw que vai com o apk"). The file
// is copied from the watch project's build output before assets are merged; if the watch
// hasn't been built yet the step is skipped gracefully instead of failing the Android build.
// Monorepo layout: the watch project is the sibling ../watch directory.
val watchPbw = file("${rootProject.projectDir}/../watch/build/watch.pbw")
val bundleWatchPbw = tasks.register<Copy>("bundleWatchPbw") {
    from(watchPbw)
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "steer.pbw" }
    onlyIf { watchPbw.exists() }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(bundleWatchPbw)
}

dependencies {
    // Classic PebbleKit — fallback transport: the original Pebble app, and Core running a
    // watchapp build without `companionApp` (see PebbleEmitter).
    implementation("com.getpebble:pebblekit:4.0.1")
    // PebbleKit 2 — primary transport (send, receive via SteerPebbleListenerService, autolaunch).
    // Held at 1.2.0: 1.3.x needs compileSdk 37 (a newer Android Gradle Plugin) and only adds data
    // logging / timeline actions, which Steer doesn't use. Its one relevant fix — cancellable
    // requests — is covered by Pk2Link's own timeout.
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // Horizontal tabbed layout (segmented control + swipeable pages).
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
