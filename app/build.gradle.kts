plugins { id("com.android.application") }
val ngrokNativeClasses = configurations.create("ngrokNativeClasses") { isTransitive=false }
val androidNgrokJar = tasks.register<Jar>("androidNgrokJar") {
    archiveBaseName.set("ngrok-native-android")
    destinationDirectory.set(layout.buildDirectory.dir("ngrok-classes"))
    from({ ngrokNativeClasses.map { zipTree(it) } })
    exclude("com/ngrok/Runtime*.class")
}
android {
    namespace = "dev.pocket.mcp"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocket.mcp"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
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
dependencies { implementation(project(":android-platform")); implementation("org.nanohttpd:nanohttpd:2.3.1"); implementation("com.ngrok:ngrok-java:1.1.1"); ngrokNativeClasses("com.ngrok:ngrok-java-native:1.1.1"); implementation(files(androidNgrokJar)); testImplementation("junit:junit:4.13.2") }
