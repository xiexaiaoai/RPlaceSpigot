plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    // 核心新增：强制 Java 编译时使用 UTF-8 编码，防止插件内的中文变成乱码
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    runServer {
        minecraftVersion("1.21.11")
        // 核心新增：在 JVM 启动参数中加入 -Dfile.encoding=UTF-8
        jvmArgs("-Xms2G", "-Xmx2G", "-Dfile.encoding=UTF-8")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}