plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.birdalarm.bird_alarm"
    // compileSdk 用 36 以便编译较新通知 API（全屏通知权限等）；运行行为由下方 targetSdk 决定。
    // 需要本机已安装 Android SDK Platform 36 且 AGP 8.6+；若构建报缺少平台，请在 SDK Manager 安装。
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.birdalarm.bird_alarm"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        // 故意停留在 33（Android 13）：Android 14+ 收紧了后台启动 Activity(BAL) 与
        // 全屏通知权限，会导致锁屏闹钟无法从后台拉起全屏页（只剩横幅）。停在 33 可让
        // 闹钟可靠地从后台直接弹出全屏响铃页。代价是 Live Updates 提级胶囊可能退化为
        // 普通通知——全屏闹钟优先。
        targetSdk = 33
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}
