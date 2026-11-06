plugins { id("com.android.application") }
android {
    namespace = "bo.edu.univalle.sgcp"
    compileSdk = 36
    defaultConfig {
        applicationId = "bo.edu.univalle.sgcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("SGCP_API_URL").getOrElse("http://10.0.2.2:5081/api/v1/")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
