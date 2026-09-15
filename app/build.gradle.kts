import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Lit le fichier `.env` à la racine du projet (format KEY=VALUE, `#` pour les commentaires).
 * Les clés y sont injectées dans BuildConfig au moment de la compilation : c'est la seule
 * façon pour une app Android d'utiliser un `.env` du projet (pas d'accès au système de fichiers
 * du poste de dev à l'exécution). La variable d'environnement du même nom sert de repli.
 */
fun readDotEnv(): Map<String, String> {
    val file = rootProject.file(".env")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val key = line.substringBefore("=").trim().removePrefix("export ").trim()
            val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
            key to value
        }
}

val dotEnv = readDotEnv()
fun envValue(name: String): String = (dotEnv[name] ?: System.getenv(name) ?: "")
    .replace("\\", "\\\\").replace("\"", "\\\"")

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.lixirian.notesdefrais"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lixirian.notesdefrais"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.4.0"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${envValue("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${envValue("OPENAI_API_KEY")}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile")
            if (storePath != null && rootProject.file(storePath).exists()) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
}
