plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.worddrill.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.worddrill.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.3.4"

        // 更新检查地址（Cloudflare Pages / Workers 均可）。改这里即可换源，不用改代码。
        // 支持用英文逗号分隔配多个镜像，会按顺序尝试，第一个可用即返回，例如：
        //   "https://worddrill-update.pages.dev/update.json,https://cdn.jsdelivr.net/gh/xxx/update.json"
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"https://worddrill-update.pages.dev/update.json,https://worddrill-vercel.vercel.app/update.json\"",
        )

        // 智能解析服务（Vercel → 商汤日日新）。部署代码见 vercel/，换地址填这里即可，
        // 也可以在 App 的「设置 → 智能解析服务地址」里临时改，不用重新打包。
        buildConfigField(
            "String",
            "SMART_PARSE_URL",
            "\"https://worddrill-vercel.vercel.app/api/smart-parse\"",
        )
    }

    /**
     * 固定调试签名。
     *
     * GitHub Actions 的 runner 每次都是全新的，AGP 会临时生成一个 debug.keystore，
     * 导致每次 CI 打出来的 APK 签名都不一样，无法覆盖安装（手机上会提示「包名不一致」）。
     * 这里把一把专门用于 debug 的密钥提交进仓库，保证所有构建签名一致。
     * 注意：这只是 debug 密钥，不要用它给正式版签名。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/worddrill-debug.p12")
            storePassword = "android"
            keyAlias = "worddrill"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
