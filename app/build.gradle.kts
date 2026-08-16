import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 接続先URLはリポジトリに含めない。
// 優先順位: local.properties の timeshift.url > 環境変数 TIMESHIFT_URL > プレースホルダ
val timeShiftUrl: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val url = props.getProperty("timeshift.url")
        ?: System.getenv("TIMESHIFT_URL")
    if (url.isNullOrBlank()) {
        logger.warn(
            "timeshift.url が未設定です。local.properties に " +
                "timeshift.url=http://<サーバ>:<ポート> を追加してください。"
        )
        "http://localhost:9360"
    } else {
        url
    }
}

android {
    namespace = "net.enjoytech.timeshiftradio"
    // androidx.core 1.19.0 / lifecycle 2.11.0 が API 37 以上でのコンパイルを要求する
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.enjoytech.timeshiftradio"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TARGET_URL", "\"$timeShiftUrl\"")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}