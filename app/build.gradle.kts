plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tvplayer"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.8.0" // alignez la version FFmpeg ci-dessous sur celle-ci

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // Décodeur logiciel FFmpeg : AC3, E-AC3 (Dolby Digital / Plus), TrueHD, DTS, DTS-HD, AAC...
    // Publié par Jellyfin sur Maven Central (vérifiez que la version existe),
    // ou compilez vous-même le module decoder_ffmpeg de Media3 et utilisez :
    // implementation(files("libs/media3-decoder-ffmpeg.aar"))
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
