plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ru.ruscrafting"
version = "0.7.0"
description = "Cross-server rank progression for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content {
            includeGroup("ru.ruscrafting.arc")
            includeGroup("ru.ruscrafting.thirdparty")
        }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.3.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.3.0")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.3.0")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.3.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.3.0")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.Zrips:CMI-API:9.8.6.4")
    // Exact private API baseline for the active 10.x EliteMobs runtimes; provided by the server.
    compileOnly("ru.ruscrafting.thirdparty:elitemobs-api:10.1.1")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.3.0")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("me.clip:placeholderapi:2.12.3")
    testImplementation("com.github.Zrips:CMI-API:9.8.6.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.3.0")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    processResources {
        inputs.property("pluginVersion", project.version.toString())
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        systemProperty("arcranks.projectDir", projectDir.absolutePath)
    }
    register<Test>("integrationTest") {
        description = "Runs disposable MySQL integration tests."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
    }
    check { dependsOn(shadowJar, "integrationTest") }
}
