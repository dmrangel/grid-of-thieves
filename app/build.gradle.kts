import java.util.Properties

plugins {
    id("com.android.application")
}

// Assinatura de release lida de keystore.properties (git-ignored), se existir:
//   storeFile=/caminho/para/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Sem ele, o release cai pra chave de debug e continua instalável.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.dmrangel.gridofthieves"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dmrangel.gridofthieves"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 + remoção de recursos: o APK de release fica só com o que o app usa.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources {
            // Metadados do Kotlin que só o kotlin-reflect usa.
            excludes += setOf("/kotlin/**", "/META-INF/*.version", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Nenhuma dependência no app: só framework do Android, para ficar o mais leve possível.
    testImplementation("junit:junit:4.13.2")
}
