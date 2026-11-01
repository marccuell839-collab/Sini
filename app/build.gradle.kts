plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dz.sini.jarvis"
    compileSdk = 35
    defaultConfig { applicationId = "dz.appnova.dexterlab"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}