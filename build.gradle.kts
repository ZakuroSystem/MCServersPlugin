plugins {
    java
}

group = "jp.mcservers"
version = "1.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("mcservers-connector")
    archiveClassifier.set("")
}
