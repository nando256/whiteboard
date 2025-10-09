import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    java
    `maven-publish`
    id("io.papermc.hangar-publish-plugin") version "0.1.3"
}

group = "net.nando256"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version as String)
        channel.set("Snapshot") // We're using the 'Snapshot' channel
        // TODO: Edit the project name to match your Hangar project
        id.set("whiteboard")
        apiKey.set(System.getenv("HANGAR_API_TOKEN"))
        platforms {
            // TODO: Use the correct platform(s) for your plugin
            register(Platforms.PAPER) {
                // TODO: If you're using ShadowJar, replace the jar lines with the appropriate task:
                //   jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                // Set the JAR file to upload
                jar.set(tasks.jar.flatMap { it.archiveFile })

                // Set platform versions from gradle.properties file
                val versions: List<String> = (property("paperVersion") as String)
                        .split(",")
                        .map { it.trim() }
                platformVersions.set(versions)

                // TODO: Configure your plugin dependencies, if any
                dependencies {
                    // Example for a dependency found on Hangar
                    hangar("Maintenance") {
                        required.set(false)
                    }
                    // Example for an external dependency
                    url("Debuggery", "https://github.com/nando256/whiteboard/") {
                        required.set(true)
                    }
                }
            }
        }
    }
}
