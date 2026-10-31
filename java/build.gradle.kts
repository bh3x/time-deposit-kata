plugins {
    java
    jacoco
}

group = "org.ikigaidigital"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Spring Boot's dependency management as a platform (BOM), without the Boot plugin: the plugin's bootJar task
    // needs a main class, which arrives with the first adapter step (ADR-0007, ADR-0008).
    implementation(platform(libs.spring.boot.dependencies))
    // Stereotype annotations for @UseCase (ADR-0008); the application layer may use nothing else from Spring.
    implementation(libs.spring.context)
    // Persistence (ADR-0006, ADR-0008): JPA for the adapters, Flyway for the schema, PostgreSQL as the database.
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Compiler warnings fail the build (CLAUDE.md rule 35).
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

testing {
    suites {
        // Unit and architecture tests: no Docker needed (ADR-0007).
        val test = named<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(libs.assertj.core)
                implementation(libs.archunit.junit5)
            }
        }

        // Integration tests on real PostgreSQL via Testcontainers (ADR-0009).
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(platform(libs.spring.boot.dependencies))
                implementation(libs.assertj.core)
                implementation(libs.spring.boot.starter.data.jpa.test)
                implementation(libs.spring.boot.testcontainers)
                implementation(libs.testcontainers.postgresql)
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

// The integration tests see the main code's dependencies (Spring, JPA, the driver), not only its API.
configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

// Coverage report only, no threshold: evidence that the characterization tests reach every branch.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}
