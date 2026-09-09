plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    id("io.github.drownek.plugwright") version "2.0.4"
}

group = "ru.ruscrafting"
version = "0.13.6"
description = "Cross-server rank progression for RusCrafting"

val e2eArcJar = providers.gradleProperty("e2eArcJar")
    .orElse(layout.projectDirectory.file("e2e-arc/build/libs/ARC-1.4.3.jar").asFile.absolutePath)

val dialogPreviewAdapter = providers.gradleProperty("dialogPreviewArcJar").orNull?.let { arcJar ->
    tasks.register<Sync>("extractDialogPreviewAdapter") {
        from(zipTree(arcJar)) {
            include("ru/arc/gui/DialogTables*", "ru/arc/gui/DialogTextLayout*", "fonts/dialog-font-metrics.json")
        }
        into(layout.buildDirectory.dir("dialog-preview-adapter"))
    }
}

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
    implementation("ru.ruscrafting.arc:arc-core:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.4")
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
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.4")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("me.clip:placeholderapi:2.12.3")
    testImplementation("com.github.Zrips:CMI-API:9.8.6.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.4")
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
        dialogPreviewAdapter?.let { adapter ->
            dependsOn(adapter)
            classpath += files(adapter.map { it.destinationDir })
            systemProperty("arcranks.dialogPreview", layout.buildDirectory.dir("reports/dialog-tables/export").get().asFile.absolutePath)
        }
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

plugwright {
    minecraftVersion.set("26.1.2")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    downloadPlugins {
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
        url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
        url("https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/rediseconomy/4.5.12/rediseconomy-4.5.12.jar")
    }
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ARC-1.4.3.jar", file(e2eArcJar.get()))
        file("plugins/ARC/modules/command-hide.yml", projectDir.resolve("src/test/e2e/fixtures/arc-command-hide.yml"))
        file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/arc-redis.yml"))
        file("plugins/RedisEconomy/config.yml", projectDir.resolve("src/test/e2e/fixtures/rediseconomy.yml"))
        file("plugins/ArcRanks/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml").readText())
    }
}
