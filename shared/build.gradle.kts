plugins { kotlin("multiplatform"); id("com.android.library") }
kotlin {
    androidTarget()
    jvm()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
android { namespace = "dev.hram.mobileledger.shared"; compileSdk = 35; defaultConfig { minSdk = 26 } }
