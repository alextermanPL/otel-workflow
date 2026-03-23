import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    kotlin("plugin.allopen") version "2.1.21"
    id("io.quarkus") version "3.27.2"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://packages.confluent.io/maven/")
    }
}

// Ensure consistent Java/Kotlin toolchains across all modules
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    // Align Kotlin toolchain with Java toolchain
    jvmToolchain(21)

    // Extension level
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all", "-Xemit-jvm-type-annotations"))
        jvmTarget = JvmTarget.fromTarget("21")
        javaParameters = true
    }
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.27.2"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.quarkus:quarkus-info")
    implementation("io.quarkus:quarkus-config-yaml")
//    implementation("io.quarkus:quarkus-logging-json")
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-rest-client-jackson")


    implementation("io.quarkus:quarkus-smallrye-fault-tolerance")
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-extension-trace-propagators")
    implementation("io.opentelemetry:opentelemetry-extension-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.0")
    implementation("org.xerial.snappy:snappy-java:1.1.10.7")
    implementation("com.google.crypto.tink:tink:1.9.0")
    implementation("io.quarkiverse.temporal:quarkus-temporal:0.2.1")






    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:2.1.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:2.1.21")

    // Testing dependencies
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkiverse.temporal:quarkus-temporal-test:0.2.1")
    testImplementation("org.amshove.kluent:kluent:1.73")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("io.quarkiverse.wiremock:quarkus-wiremock-test:1.4.1")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}

// Configure test task
tasks.test {
    description = "Runs unit and integration tests"
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    useJUnitPlatform()
}










/**
 * Quarkus common configuration reused in all services.
 */
val quarkusCommonConfig = """
    # Quarkus common configuration. This is auto generated file by build.gradle.kts.
    quarkus:
        analytics:
            disabled: true
        log:
            level: INFO
            category:
                com.luminor.workflow.testworkflow:
                    level: DEBUG
            console:
                format: '%p (%d{yyyy-MM-dd HH:mm:ss,SSS}) [%X] [%t] [%c:%L]: %s%e%n'

    "%test":
        quarkus:
            log:
                console:
                    json: false

    "%dev":
        quarkus:
            log:
                console:
                    json: false

    "%prod":
        quarkus:
            log:
                console:
                    json:
                        excluded-keys:
                            - sequence
""".trimIndent()

tasks.processResources {
    doFirst {
        val commonConfigDir = "${sourceSets.main.get().output.resourcesDir!!}/META-INF"

        // File name taken from https://quarkus.io/guides/config-reference#configuration-sources
        val commonConfigFilePath = "$commonConfigDir/microprofile-config.yml"

        mkdir(commonConfigDir)
        file(commonConfigFilePath).writeText(quarkusCommonConfig)
    }

    outputs.upToDateWhen { false }
}
