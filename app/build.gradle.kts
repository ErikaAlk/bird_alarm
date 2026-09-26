plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.birdalarm.bird_alarm"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        // 包名和签名都沿用 Flutter 版：覆盖安装才能保住闹钟、音库和设置（首次启动从 Flutter 的存储迁过来）
        applicationId = "com.birdalarm.bird_alarm"
        minSdk = 26
        // Live Updates 要 36。锁屏全屏响铃靠 MainActivity 的 showWhenLocked + 应用内响铃页，与 36 兼容（见 CLAUDE.md）
        targetSdk = 36
        // Flutter 版按架构拆包时 versionCode = 架构号 × 1000 + 版本号（手机上的 arm64 包是 2040，x86_64 是 4040），
        // 新版要比它们都大才能覆盖安装，所以从 5041 起，之后每发一版加一
        versionCode = 5044
        versionName = "2.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 一直用调试签名出包：换签名的话装不上旧版，只能卸载重装，闹钟和设置全丢
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    // 界面只用设计库的组件和 token（全局 DESIGN.md 第 2 章），includeBuild 替换成本地模块
    implementation("click.erikaalk.coloroskit:kit")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在本地单测里只是空壳
    testImplementation("org.json:json:20240303")
}
