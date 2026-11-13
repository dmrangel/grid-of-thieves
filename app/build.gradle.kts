plugins {
    id("com.android.application")
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

    buildTypes {
        release {
            // R8 + remoção de recursos: o APK de release fica só com o que o app usa.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // App pessoal, instalado direto: assina com a chave de debug para continuar instalável.
            signingConfig = signingConfigs.getByName("debug")
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
