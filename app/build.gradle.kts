import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing, never committed. Looked up in order:
 *  1. env vars SW_KEYSTORE_FILE / SW_KEYSTORE_PASSWORD / SW_KEY_ALIAS / SW_KEY_PASSWORD (CI)
 *  2. ~/.storyweaver/keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
 * Without either, release builds are produced unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val env = System.getenv()
    if (!env["SW_KEYSTORE_FILE"].isNullOrBlank()) {
        return@run mapOf(
            "storeFile" to env.getValue("SW_KEYSTORE_FILE"),
            "storePassword" to env["SW_KEYSTORE_PASSWORD"].orEmpty(),
            "keyAlias" to (env["SW_KEY_ALIAS"] ?: "storyweaver"),
            "keyPassword" to (env["SW_KEY_PASSWORD"] ?: env["SW_KEYSTORE_PASSWORD"].orEmpty()),
        )
    }
    val file = File(System.getProperty("user.home"), ".storyweaver/keystore.properties")
    if (!file.isFile) return@run null
    val props = Properties().apply { file.inputStream().use { load(it) } }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

android {
    namespace = "com.storyweaver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.storyweaver.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 code + resource shrinking: smaller, faster APK than the debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":source:literotica"))
    implementation(project(":epub"))
    implementation(project(":feature:create"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:merge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
