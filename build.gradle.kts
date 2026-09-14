plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "org.kert0n"
version = "0.0.1-SNAPSHOT"
description = "MedAppServer"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    // Стартер под Spring Boot 4 — отдельный артефакт, как вофициальном samples/exposed-spring.
    // Обычный exposed-spring-boot-starter собран под Boot 3 и ищет автоконфигурацию по
    // старым адресам.
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-kotlinx-serialization-json")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("com.sksamuel.aedile:aedile-core:3.0.4")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

/**
 * Набор про планы запросов — отдельным source set, а не внутри `test`.
 *
 * Фикстур на десятки тысяч строк поднимается около минуты, и платить её на каждом обычном
 * прогоне незачем. Запускается своей задачей и в CI отдельным шагом.
 */
val queryPlanTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["queryPlanTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["queryPlanTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

val queryPlanTestTask = tasks.register<Test>("queryPlanTest") {
    description = "Проверяет планы запросов на большом объёме"
    group = "verification"
    testClassesDirs = queryPlanTest.output.classesDirs
    classpath = queryPlanTest.runtimeClasspath
    // После обычных тестов: если сломано простое, планы смотреть рано.
    shouldRunAfter(tasks.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Снимки перезаписываются вместо сверки:
    //     ./gradlew test -DupdateOpenApi=true
    //     ./gradlew test -DupdateSchema=true
    systemProperty("updateOpenApi", System.getProperty("updateOpenApi") ?: "false")
    systemProperty("updateSchema", System.getProperty("updateSchema") ?: "false")
    // Tests read these files, so a change to any of them must invalidate the results.
    // Without this a broken db/schema.sql leaves the previous green run in place: the task
    // is up to date because none of them is on the classpath. README.md is here for the same
    // reason — its route list is checked against the contract, and an edit that breaks the
    // check would otherwise be reported as an up-to-date green run.
    inputs.file("db/schema.sql").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("open-api.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("README.md").withPathSensitivity(PathSensitivity.RELATIVE)
}
