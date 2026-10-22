import java.util.Properties

plugins { id("com.android.application") }

// ---------------------------------------------------------------------------
// 可选签名配置
// 在仓库根目录放一个 keystore.properties（已在 .gitignore 中忽略）即可构建
// 正式签名的 release 包；示例见 keystore.properties.example。
// 没有该文件时 release 包将以未签名形式产出（CI 只构建 debug）。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
        keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.legacy.localsend"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.legacy.localsend"
        // Android 2.2 (Froyo) 为最低兼容版本
        minSdk = 8
        targetSdk = 30
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Android 2.2 只认 v1 (JAR) 签名
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 使用 AGP 默认 debug keystore
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
