import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.Project
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

val sdkGroupId = providers.gradleProperty("sdk.group").orElse("com.xiaobai.livephoto")
val sdkArtifactId = providers.gradleProperty("sdk.artifact").orElse("livephoto-compat-sdk")
val sdkVersion = providers.gradleProperty("sdk.version").orElse("1.0.0")

group = sdkGroupId.get()
version = sdkVersion.get()

android {
    namespace = "com.xiaobai.livephotoutil"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    repositories {
        mavenLocal()
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = sdkGroupId.get()
                artifactId = sdkArtifactId.get()
                version = sdkVersion.get()
                from(components["release"])
                pom {
                    name.set("LivePhotoCompat SDK")
                    description.set("Cross-vendor LivePhoto compatibility SDK for Android.")
                }
            }
        }
    }
}

val apiBaselineFile = layout.projectDirectory.file("api/livephoto-compat.api")
val apiCurrentFile = layout.buildDirectory.file("reports/api/current.api")

fun extractClassesJarFromAar(aarFile: java.io.File, classesJarFile: java.io.File) {
    classesJarFile.parentFile?.mkdirs()
    ZipFile(aarFile).use { zip ->
        val entry = zip.getEntry("classes.jar")
            ?: error("classes.jar not found in AAR: $aarFile")
        zip.getInputStream(entry).use { input ->
            classesJarFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

fun Project.buildPublicApiSignature(classesJarFile: java.io.File): String {
    val classNames = mutableListOf<String>()
    ZipFile(classesJarFile).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") }
            .filterNot { it.name.contains('$') }
            .filterNot { it.name.endsWith("BuildConfig.class") }
            .map { it.name.removeSuffix(".class").replace('/', '.') }
            .filter { it.startsWith("com.xiaobai.livephotoutil.compat.") }
            .sorted()
            .forEach { classNames += it }
    }

    val output = StringBuilder()
    classNames.forEach { className ->
        val process = ProcessBuilder(
            "javap",
            "-classpath",
            classesJarFile.absolutePath,
            "-public",
            className
        ).redirectErrorStream(true).start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "javap failed for $className (exit=$exitCode):\n$stdout"
        }
        val normalized = stdout
            .lineSequence()
            .filterNot { line -> line.startsWith("Compiled from ") }
            .filterNot { line -> line.contains(" access$") }
            .joinToString("\n")
            .trim()
        output.append("### ").append(className).append('\n')
        output.append(normalized).append('\n').append('\n')
    }
    return output.toString().trimEnd()
}

val generatePublicApiSnapshot by tasks.registering {
    group = "verification"
    description = "Generate current public API signature snapshot for binary compatibility checks."
    dependsOn("bundleReleaseAar")
    doLast {
        val aarFile = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar").get().asFile
        require(aarFile.exists()) { "Release AAR not found: $aarFile" }
        val classesJar = layout.buildDirectory.file("tmp/api/classes.jar").get().asFile
        extractClassesJarFromAar(aarFile, classesJar)
        val signature = project.buildPublicApiSignature(classesJar)
        val outFile = apiCurrentFile.get().asFile
        outFile.parentFile?.mkdirs()
        outFile.writeText(signature)
    }
}

val checkBinaryCompatibility by tasks.registering {
    group = "verification"
    description = "Check current public API signature against baseline."
    dependsOn(generatePublicApiSnapshot)
    doLast {
        val baseline = apiBaselineFile.asFile
        val current = apiCurrentFile.get().asFile
        require(current.exists()) { "Current API signature file is missing: $current" }
        if (!baseline.exists()) {
            throw GradleException(
                "API baseline is missing: $baseline. Run :app:updateApiBaseline to create it."
            )
        }
        val baselineText = baseline.readText()
        val currentText = current.readText()
        if (baselineText != currentText) {
            throw GradleException(
                "Public API changed. Run :app:updateApiBaseline if this change is intentional and versioned."
            )
        }
    }
}

val updateApiBaseline by tasks.registering {
    group = "verification"
    description = "Update API baseline file from current snapshot."
    dependsOn(generatePublicApiSnapshot)
    doLast {
        val baseline = apiBaselineFile.asFile
        baseline.parentFile?.mkdirs()
        baseline.writeText(apiCurrentFile.get().asFile.readText())
    }
}

tasks.named("check").configure {
    dependsOn(checkBinaryCompatibility)
}
