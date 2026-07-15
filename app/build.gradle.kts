import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val requestedTaskNames = gradle.startParameter.taskNames
fun requestedTask(taskName: String): Boolean =
    requestedTaskNames.any { it == taskName || it == ":app:$taskName" }

val gestureTestRequested = requestedTask("gestureTest")
val connectedGestureTestRequested = requestedTask("connectedGestureAndroidTest")
val connectedGestureResultsDir = layout.buildDirectory.dir("outputs/androidTest-results/connected/debug")

fun String.toFileNameSegment(): String =
    trim()
        .replace(Regex("""[^\p{Alnum}._-]+"""), "-")
        .trim('-', '.', '_')
        .ifBlank { "app" }

val releaseApkAppName = providers.fileContents(
    layout.projectDirectory.file("src/main/res/values/strings.xml")
).asText.map { stringsXml ->
    Regex("""<string\s+name="app_name">([^<]+)</string>""")
        .find(stringsXml)
        ?.groupValues
        ?.get(1)
        ?.toFileNameSegment()
        ?: "app"
}.get()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    signingConfigs {
        val storeFilePath = keystoreProperties.getProperty("storeFile")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    namespace = "one.aozora.darkhour"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "one.aozora.darkhour"
        minSdk = 28
        targetSdk = 37
        versionCode = 16
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (connectedGestureTestRequested) {
            testInstrumentationRunnerArguments["class"] =
                "one.aozora.darkhour.ui.actogram.ActogramGestureInstrumentedTest"
            testInstrumentationRunnerArguments["gestureFrames"] = "true"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("Boolean", "USE_DEMO_DATA", "false")
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("Boolean", "USE_DEMO_DATA", "true")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("Boolean", "USE_DEMO_DATA", "false")
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("$releaseApkAppName-${output.versionName.get()}.apk")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("gestureTest") {
    group = "verification"
    description = "Runs JVM actogram gesture tests and prints per-frame diagnostic tables."
    dependsOn("testDebugUnitTest")
}

tasks.register("connectedGestureAndroidTest") {
    group = "verification"
    description = "Runs connected actogram gesture tests and enables per-frame diagnostics."
    dependsOn("connectedDebugAndroidTest")
    val resultsDir = connectedGestureResultsDir
    doLast {
        val resultDir = resultsDir.get().asFile
        val logFiles = if (resultDir.isDirectory) {
            resultDir.walkTopDown()
                .filter { it.isFile }
                .filter {
                    it.name.startsWith("logcat-one.aozora.darkhour.ui.actogram.ActogramGestureInstrumentedTest-") &&
                        it.extension == "txt"
                }
                .sortedBy { it.name }
                .toList()
        } else {
            emptyList()
        }
        if (logFiles.isEmpty()) {
            println("No connected gesture logcat files found under ${resultDir.absolutePath}")
        }
        logFiles.forEach { logFile ->
            val gestureLines = logFile.readLines()
                .filter { it.contains("GestureFrames") }
                .map { it.substringAfter("GestureFrames:").trim() }
            if (gestureLines.isNotEmpty()) {
                println()
                println("Connected gesture diagnostics from ${logFile.name}:")
                gestureLines.forEach(::println)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    if (gestureTestRequested && name == "testDebugUnitTest") {
        filter {
            includeTestsMatching("one.aozora.darkhour.ui.actogram.ActogramGesture*")
        }
        systemProperty("darkhour.gestureFrames", "true")
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events("passed", "failed", "standardOut", "standardError")
        }
    }
}
