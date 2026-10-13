plugins { id("com.android.application") }
android {
    namespace = "dev.pocket.mcp"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocket.mcp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("distribution") {
            storeFile = file(System.getenv("APK_KEYSTORE") ?: "missing-keystore.jks")
            storePassword = System.getenv("APK_STORE_PASSWORD")
            keyAlias = "pocket-mcp"
            keyPassword = System.getenv("APK_KEY_PASSWORD")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("distribution"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    splits { abi { isEnable=true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk=true } }
    packaging { resources { excludes += "META-INF/DEPENDENCIES" } }
    lint { textReport=true; textOutput=file("build/reports/lint-results-release.txt") }
}
dependencies { implementation(project(":android-platform")); implementation("org.nanohttpd:nanohttpd:2.3.1"); implementation("com.ngrok:ngrok-java:1.1.1"); implementation("org.slf4j:slf4j-api:2.0.13") }
