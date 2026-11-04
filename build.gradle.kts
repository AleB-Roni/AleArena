plugins {
    java
}

group = "de.roni"
version = "1.0.0"

val paperApi = providers.gradleProperty("paperApi").getOrElse("26.3.build.+")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.5.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("AleArena-${project.version}.jar")
}
