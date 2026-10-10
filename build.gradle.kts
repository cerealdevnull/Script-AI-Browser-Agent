import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import proguard.gradle.ProGuardTask
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.cereal-automation.com/releases")
    }
}

// Provided by the Cereal host through ScriptClassLoader (stdlib by parent fallback, sdk and coroutines parent-first).
// Bundling them breaks linking: ProGuard would rename the bundled stdlib, and the host's coroutines link against its own.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "com.cereal-automation", module = "cereal-sdk")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

tasks.withType<ShadowJar> {
    archiveFileName.set("release.jar")
    // zstd-jni natives (pulled in by ktor's Apache engine) are ~11 MB and never used; signatures break the merged jar.
    exclude(
        "**/*.so", "**/*.dylib", "**/*.dll", "**/*.jnilib",
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class",
    )
}

val scriptJar =
    tasks.register("scriptJar", ProGuardTask::class.java) {
        description = "Build script jar with obfuscation"
        dependsOn("shadowJar")

        val buildDir = layout.buildDirectory.get()
        val cerealScriptFolder = "$buildDir/cereal"

        injars("$buildDir/libs/release.jar")
        outjars("$cerealScriptFolder/release.jar")

        // Mapping for debugging
        printseeds("$cerealScriptFolder/seeds.txt")
        printmapping("$cerealScriptFolder/mapping.txt")

        // Dependencies (stdlib, coroutines and the sdk come from the host)
        libraryjars(sourceSets.main.get().compileClasspath)

        configuration(
            files(
                "${rootDir.absolutePath}/proguard-rules/script.pro",
                "${rootDir.absolutePath}/proguard-rules/coroutines.pro",
            ),
        )

        // The host reads the config form with kotlin-reflect (nullability, defaults), which needs the Kotlin metadata
        // that ProGuard strips (its -keepkotlinmetadata fails with the stdlib as a library jar). The config classes are
        // kept unrenamed and reference only kept names, so put the compiler's originals back.
        val classes = sourceSets.main.get().output.classesDirs
        doLast {
            val jar = file("$cerealScriptFolder/release.jar").toPath()
            FileSystems.newFileSystem(URI.create("jar:${jar.toUri()}"), emptyMap<String, Any>()).use { zip ->
                classes.asFileTree.matching { include("**/AgentConfiguration*.class") }.visit {
                    if (!isDirectory) {
                        val target = zip.getPath(relativePath.pathString)
                        target.parent?.let { Files.createDirectories(it) }
                        Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

buildscript {
    dependencies {
        classpath(libs.proguard.gradle)
    }
}

val cerealSdkVersion = libs.versions.cereal.sdk.get()

tasks.processResources {
    filesMatching("manifest.json") {
        filter { line -> line.replace("@cereal-sdk-version@", cerealSdkVersion) }
    }
}

dependencies {
    implementation(libs.cereal.sdk)
    implementation(libs.kdriver.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.cereal.test.utils)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(kotlin("reflect")) // ScriptJarTest reads the config like the host does
}

kotlin {
    jvmToolchain(21) // kdriver 0.6.1 is built for Java 21
    // Interface defaults as real JVM default methods (the host calls them via invokeDefault), no DefaultImpls.
    compilerOptions { jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY) }
}

tasks.test {
    useJUnitPlatform { excludeTags("scriptJar", "acceptance") }
}

// Opt-in acceptance runs on a real claude-opus-5-5 and Toolshop (docs/acceptance.md). They cost money and skip
// without ANTHROPIC_API_KEY. Pick one with --tests, e.g. ./gradlew acceptanceTest --tests '*G2LoginReadTest'.
tasks.register<Test>("acceptanceTest") {
    description = "Run the opt-in acceptance goals G1-G3 against a real model"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("acceptance") }
    outputs.upToDateWhen { false } // a real run is never up to date
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// Loads the shrunk jar through a loader that mimics the host's ScriptClassLoader and runs one goal.
tasks.register<Test>("scriptJarTest") {
    description = "Run the shrunk script jar through a host-like class loader"
    group = "verification"
    dependsOn(scriptJar)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("scriptJar", layout.buildDirectory.file("cereal/release.jar").get().asFile.absolutePath)
    // The script keeps its Chrome profiles under user.home; keep them out of the real home.
    systemProperty("user.home", layout.buildDirectory.dir("tmp/scriptJarTest-home").get().asFile.absolutePath)
    useJUnitPlatform { includeTags("scriptJar") }
}
