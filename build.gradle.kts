plugins {
    java
    kotlin("jvm")
    id("maven-publish")
}

group = "org.ferrymehdi"
version = "1.1.0"

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
    implementation("commons-io:commons-io:2.7")

    compileOnly("org.slf4j:slf4j-api:2.0.7")
    implementation("org.jsoup:jsoup:1.15.3")

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
            version = "1.1.0"
        }
    }
    repositories {
        mavenLocal()
    }
}

