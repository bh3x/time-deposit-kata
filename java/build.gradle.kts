plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
}

group = "org.ikigaidigital"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Spring Boot's dependency management as a Gradle platform (BOM): the Boot plugin alone does not manage
    // versions, and a platform constrains only the configurations that declare it (ADR-0007).
    implementation(platform(libs.spring.boot.dependencies))
    // Stereotype annotations for @UseCase (ADR-0008); the application layer may use nothing else from Spring.
    implementation(libs.spring.context)
    // Persistence (ADR-0006, ADR-0008): JPA for the adapters, Flyway for the schema, PostgreSQL as the database.
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    // REST adapter (ADR-0002): Spring MVC for the generated interface. No validator: since ADR-0013 the contract has
    // no request parameter to validate. Only the Bean Validation API, because openapi-generator puts @NotNull on the
    // required model fields even with useBeanValidation=false; nothing evaluates it.
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jakarta.validation.api)
}

// Contract first (ADR-0002, ADR-0008 rule 6): the API interface and models are generated from openapi.yaml on every
// build and never edited or committed. Only interfaces, no default methods, so an operation added to the contract
// fails compilation until a controller implements it.
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("src/main/resources/openapi/openapi.yaml")
    outputDir = generatedOpenApiDir
    apiPackage = "org.ikigaidigital.shared.adapter.in.rest.api"
    modelPackage = "org.ikigaidigital.shared.adapter.in.rest.model"
    // ADR-0002: generated models end in Dto (e.g. TimeDepositDto), so they never clash with domain classes such as
    // the legacy TimeDeposit; schema names in the contract stay suffix-free.
    modelNameSuffix = "Dto"
    // Removed or renamed schemas must not leave stale classes behind that still compile.
    cleanupOutput = true
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
        "useSpringBoot4" to "true",
        "useJackson3" to "true",
        // One interface per tag, i.e. per capability (ADR-0008 rule 6).
        "useTags" to "true",
        // No request parameters to validate since ADR-0013 (see the dependencies above).
        "useBeanValidation" to "false",
        // JSpecify, as Spring Framework 7 uses: its org.springframework.lang.Nullable is deprecated (-Werror).
        "useJspecify" to "true",
        // The base path comes from the contract's server URL (/api/v1), not from the controller.
        "requestMappingMode" to "api_interface",
        // Reproducible output: no build timestamp in @Generated.
        "hideGenerationTimestamp" to "true",
        // No jackson-databind-nullable dependency: the contract has no nullable fields.
        "openApiNullable" to "false",
        // ADR-0005: the application publishes no documentation, so no springdoc or Swagger annotations.
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
    )
}

// Derived from the task, so every task that consumes the main sources (e.g. compileJava) depends on the generator
// without an explicit dependsOn.
sourceSets {
    main {
        java.srcDir(tasks.named("openApiGenerate").map { generatedOpenApiDir.get().dir("src/main/java") })
    }
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
                // Web slice tests: @WebMvcTest, MockMvcTester, @MockitoBean (ADR-0009).
                implementation(libs.spring.boot.starter.webmvc.test)
            }
        }

        // Integration tests on real PostgreSQL via Testcontainers (ADR-0009).
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                // The Boot platform comes with main's implementation configuration (extended below).
                implementation(project())
                implementation(libs.assertj.core)
                implementation(libs.spring.boot.starter.data.jpa.test)
                implementation(libs.spring.boot.testcontainers)
                implementation(libs.testcontainers.postgresql)
                // End-to-end tests over HTTP (ADR-0009).
                implementation(libs.spring.boot.resttestclient)
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
    // Both suites (ADR-0009): unit and architecture tests plus the Testcontainers integration tests.
    dependsOn(tasks.named("test"), tasks.named("integrationTest"))
    executionData(tasks.named("test").get(), tasks.named("integrationTest").get())
    // Generated OpenAPI code is not ours to cover; it would only dilute the report.
    classDirectories.setFrom(sourceSets.main.get().output.classesDirs.asFileTree.matching {
        exclude("org/ikigaidigital/shared/adapter/in/rest/api/**", "org/ikigaidigital/shared/adapter/in/rest/model/**")
    })
    reports {
        xml.required = true
        html.required = true
    }
}
