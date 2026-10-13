plugins {
    id("com.android.application")
}

android {
    namespace = "ai.closepaw.mcp.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.closepaw.mcp.fixture"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
