import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

group = "ru.ruscrafting.arc"
version = "0.1.1"
description = "Typed quest progress, clan membership and ownership integration contracts"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "arc-ranks-api"
            pom {
                name.set("ArcRanks API")
                description.set(project.description)
                url.set("https://github.com/alexey-va/ArcRanks")
            }
        }
    }
}
