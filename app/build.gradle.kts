plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}
android {
    namespace = "bo.edu.univalle.sgcp"
    compileSdk = 36
    defaultConfig {
        applicationId = "bo.edu.univalle.sgcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "5.2.0"
        buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("SGCP_API_URL").getOrElse("http://10.0.2.2:5081/api/v1/")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.4")
}
