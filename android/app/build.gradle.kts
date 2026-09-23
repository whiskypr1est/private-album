// ============================================================
//  app 模块 —— 私有相册（安卓客户端）
// ============================================================
plugins {
    id("com.android.application")
}

android {
    namespace = "com.privatealbum.app"
    compileSdk = 36

    // 锁定 build-tools：AGP 默认会去 dl.google.com 下载 35.0.0，本机访问 Google 不稳定
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.privatealbum.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 11
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            // 演示阶段复用 debug 签名，方便直接安装；正式发布请换成自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 源码里有大量中文注释：Windows 默认 GBK，不指定会被 javac 当作非法字符
        encoding = "UTF-8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 让 javac 用英文报错（中文报错在 Windows 控制台会乱码，没法排查）
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.forkOptions.jvmArgs = listOf(
            "-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8"
        )
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*"
        )
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.11.0")

    // 图片加载与三级缓存（内存 / 磁盘 / 网络）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
}

