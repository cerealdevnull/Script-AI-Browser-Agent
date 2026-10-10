import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import proguard.gradle.ProGuardTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

allprojects {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.cereal-automation.com/releases")
        }
    }

    // Exclude these dependencies because they are available in cereal script runtime.
    configurations.runtimeClasspath {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "com.cereal-automation", module = "cereal-sdk")
    }

    apply(plugin = "com.gradleup.shadow")

    tasks.withType<ShadowJar> {
        archiveFileName.set("release.jar")
    }

    tasks.register("scriptJar", ProGuardTask::class.java) {
        description = "Build script jar with obfuscation"
        dependsOn("shadowJar")

        val artifactName = "release.jar"
        val buildDir = layout.buildDirectory.get()
        val cerealScriptFolder = "$buildDir/cereal"

        injars("$buildDir/libs/$artifactName")
        outjars("$cerealScriptFolder/$artifactName")

        // Mapping for debugging
        printseeds("$cerealScriptFolder/seeds.txt")
        printmapping("$cerealScriptFolder/mapping.txt")

        // Dependencies
        libraryjars(sourceSets.main.get().compileClasspath)

        configuration(
            files(
                "${rootDir.absolutePath}/proguard-rules/script.pro",
                "${rootDir.absolutePath}/proguard-rules/coroutines.pro",
            ),
        )
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
    implementation(libs.cereal.licensing)

    testImplementation(libs.cereal.sdk)
    testImplementation(kotlin("test"))
    testImplementation(libs.cereal.test.utils)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.mockk)
}

tasks {
    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }
}
