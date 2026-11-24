plugins {
    kotlin("jvm") version "2.3.20"
}

group = "kr.lifetime"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.254-stable")
    compileOnly("io.github.Shai0717:Kolor:1.0.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.254-stable")
    testImplementation("io.github.Shai0717:Kolor:1.0.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
