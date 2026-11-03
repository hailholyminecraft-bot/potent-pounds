plugins {
    java
}

group = "net.potendpound"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

sourceSets {
    main {
        java.setSrcDirs(listOf("."))
        java.include("*.java")
        resources.setSrcDirs(listOf("."))
        resources.include("plugin.yml", "config.yml")
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
}
