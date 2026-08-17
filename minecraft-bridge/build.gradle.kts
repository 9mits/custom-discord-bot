plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "bot.mgx"
version = "2.67.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // compileOnly at runtime is absent, so tests touching LuckPermsService need the API.
    testImplementation("net.luckperms:api:5.4")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveFileName.set("MGXAccessBridge.jar")
        relocate("com.google.gson", "bot.mgx.accessbridge.lib.gson")
        minimize()
    }
    build {
        dependsOn(shadowJar)
    }
}
