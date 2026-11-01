import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.hermeticacomms.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Optional: push needs Firebase Messaging, geofences need Play Services Location. The app brings them.
    compileOnly("com.google.firebase:firebase-messaging:24.1.0")
    compileOnly("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.hermeticacomms", "hermetica", sdkVersion)
    pom {
        name.set("Hermetica Android SDK")
        description.set("Events, identity, push, in-app messages and geofences for Hermetica.")
        url.set("https://github.com/hermeticacomms/hermetica-android")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("hermeticacomms")
                name.set("Hermetica")
                url.set("https://github.com/hermeticacomms")
            }
        }
        scm {
            url.set("https://github.com/hermeticacomms/hermetica-android")
            connection.set("scm:git:https://github.com/hermeticacomms/hermetica-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/hermeticacomms/hermetica-android.git")
        }
    }
}
