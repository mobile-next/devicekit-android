plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

// version name is supplied by the git tag at release time (-PversionName=x.y.z);
// non-release builds fall back to a dev placeholder. versionCode is derived from it.
val appVersionName = providers.gradleProperty("versionName").orNull ?: "0.0.0-dev"
val appVersionCode = appVersionName
    .substringBefore("-")
    .split(".")
    .mapNotNull { it.toIntOrNull() }
    .let { if (it.size == 3) it[0] * 10000 + it[1] * 100 + it[2] else 1 }
    .coerceAtLeast(1)

android {
    namespace = "com.mobilenext.devicekit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilenext.devicekit"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isDebuggable = false  // Disable debugging to allow ProGuard optimizations
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
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
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.tracing)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.uiautomator) {
        exclude(group = "junit", module = "junit")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Configure Detekt
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "11"
}

// Configure Android Lint
android.lint {
    textReport = true
    abortOnError = false
}
