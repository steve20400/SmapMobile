plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cm.smap.collector"
    compileSdk = 34

    defaultConfig {
        applicationId = "cm.smap.collector"
        minSdk = 26                    // Android 8.0 — couvre les Tecno/Infinix du terrain
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // Interface — Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Stockage local chiffré — Room + SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Synchronisation résiliente
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Réseau + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Position
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Carte — osmdroid, et non Google Maps : fonds OpenStreetMap sans clé ni
    // facturation, et surtout capable de servir des tuiles hors ligne depuis
    // un fichier local, ce qui est la cible pour le terrain camerounais.
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Intérieur (expérimental) — ARCore + lecture de QR
    implementation("com.google.ar:core:1.44.0")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Caméra pour la pose d'ancres. On scanne les codes DÉJÀ présents dans le
    // bâtiment — étiquettes d'inventaire, tableaux électriques, planches QR
    // existantes — plutôt que d'en imprimer, ce qui a un coût réel sur le
    // terrain. Le serveur ne distingue pas l'origine du code.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
