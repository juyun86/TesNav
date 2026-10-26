plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.autonavi.amapauto.gdarcameraservicedemo"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.autonavi.amapauto.gdarcameraservicedemo"
        minSdk = 27
        targetSdk = 37
        versionCode = 12
        versionName = "0.12-tenengkai-source-readiness"
        testInstrumentationRunner = "com.autonavi.amapauto.gdarcameraservicedemo.SessionRecoveryTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
