plugins {
    java
    alias(libs.plugins.lavalink)
    kotlin("jvm")
    id("maven-publish")
}

group = "org.ferrymehdi"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

dependencies {
    implementation("dev.arbjerg:lavaplayer:2.1.0")
    compileOnly("org.slf4j:slf4j-api:2.0.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("commons-io:commons-io:2.7")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    implementation("org.springframework:spring-context:6.0.2")
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
    maven (url = "https://maven.lavalink.dev/releases")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "org.ferrymehdi"
            artifactId = "skybot-lavaplayer-plugin"
            version = "1.0.0"
        }
    }
    repositories {
        mavenLocal()
    }
}
