buildscript {
    repositories { mavenCentral() }
    dependencies {
        // Used at build time by the jOOQ codegen task to spin up an ephemeral
        // Postgres + run Flyway against it before invoking the generator.
        // embedded-postgres ships native Postgres binaries — no Docker required
        // for builds. (Tests still use Testcontainers; this is build-time only.)
        classpath("io.zonky.test:embedded-postgres:2.1.0")
        classpath(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.0.0"))
        classpath("io.zonky.test.postgres:embedded-postgres-binaries-darwin-amd64")
        classpath("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
        classpath("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64")
        classpath("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
        classpath("org.flywaydb:flyway-core:11.1.0")
        classpath("org.flywaydb:flyway-database-postgresql:11.1.0")
        classpath("org.postgresql:postgresql:42.7.5")
    }
}

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("gg.jte.gradle") version "3.2.4"
    id("nu.studer.jooq") version "10.0"
}

group = "org.dev"
version = "0.0.1-SNAPSHOT"
description = "orange"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.0-M5"
extra["temporalVersion"] = "1.23.2"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("gg.jte:jte-spring-boot-starter-4:3.2.4")
    implementation("io.github.wimdeblauwe:htmx-spring-boot:5.1.0")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("io.temporal:temporal-spring-boot-starter-alpha:${property("temporalVersion")}")
    implementation("org.kohsuke:github-api:1.330")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // Postgres driver: needed at compile time so PgListenerService can use the
    // vendor-specific PGConnection / PGNotification types for LISTEN/NOTIFY.
    implementation("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("io.temporal:temporal-testing:${property("temporalVersion")}")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")

    // jOOQ code generator runs against the embedded Postgres provisioned in
    // the generateJooq doFirst (see below). It only needs the JDBC driver here.
    jooqGenerator("org.postgresql:postgresql")
}

// The jOOQ Gradle plugin defaults to a newer jOOQ codegen than Spring Boot's BOM
// pins for jooq/jooq-meta, which causes NoSuchMethodError between the two. Pin
// every org.jooq:* artifact on the codegen classpath to the BOM-managed version.
configurations.named("jooqGenerator") {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jooq") useVersion("3.19.32")
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

jte {
    generate()
    binaryStaticContent = true
}

// Load .env into the bootRun task and tests so secrets stay out of source/control
// while still being picked up by `./gradlew bootRun` and `./gradlew test`.
fun parseDotEnv(): Map<String, String> {
    val f = file(".env")
    if (!f.exists()) return emptyMap()
    return f.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx < 1) null
            else {
                val key = line.substring(0, idx).trim()
                val rawValue = line.substring(idx + 1).trim()
                val value = when {
                    rawValue.startsWith("\"") && rawValue.endsWith("\"") -> rawValue.substring(1, rawValue.length - 1)
                    rawValue.startsWith("'") && rawValue.endsWith("'") -> rawValue.substring(1, rawValue.length - 1)
                    else -> rawValue
                }
                key to value
            }
        }
        .toMap()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    parseDotEnv().forEach { (k, v) -> environment(k, v) }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop on macOS publishes its socket under the user dir.
    // Testcontainers checks /var/run/docker.sock by default, so point it explicitly.
    val userSocket = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
    if (userSocket.exists()) {
        environment("DOCKER_HOST", "unix://${userSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", userSocket.absolutePath)
    }
}

// ──────────────────────────── jOOQ codegen ────────────────────────────
// At build time we spin up an embedded Postgres 18 (no Docker required), run
// our Flyway migrations against it, and point jOOQ at it. This handles every
// Postgres-specific feature we use (uuidv7(), PL/pgSQL trigger functions,
// JSONB, partial indexes). embedded-postgres bundles native binaries.
// Tests still use Testcontainers — this affects only build-time codegen.
// macOS ships with a tiny shared-memory cap (sysctl kern.sysv.shmall=1024 pages,
// ~4MB) that's well below postgres's default shared_buffers of 128MB. Override
// to 16MB so initdb succeeds on a stock macOS without sudo gymnastics. Linux
// CI doesn't care but the override is harmless there too.
val codegenPg by lazy {
    io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder()
        .setServerConfig("shared_buffers", "16MB")
        .setServerConfig("max_connections", "20")
        .start()
}

jooq {
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(true)
            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    // Real values are injected in generateJooq's doFirst once
                    // the container is up; placeholders here keep the plugin
                    // happy at configuration time.
                    url = "jdbc:postgresql://placeholder/placeholder"
                    user = "placeholder"
                    password = "placeholder"
                }
                generator.apply {
                    name = "org.jooq.codegen.JavaGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        excludes = "flyway_schema_history"
                    }
                    generate.apply {
                        isDeprecated = false
                        isRecords = true
                        isFluentSetters = true
                        isJavaTimeTypes = true
                    }
                    target.apply {
                        packageName = "com.ai.orange.db.jooq"
                        directory = "build/generated/sources/jooq/main/java"
                    }
                }
            }
        }
    }
}

tasks.named("generateJooq") {
    doFirst {
        val pg = codegenPg
        val ds = pg.getPostgresDatabase()
        val jdbcUrl = pg.getJdbcUrl("postgres", "postgres")

        org.flywaydb.core.Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:${projectDir}/src/main/resources/db/migration")
            .load()
            .migrate()

        val cfg = (project.extensions.getByName("jooq") as nu.studer.gradle.jooq.JooqExtension)
            .configurations.getByName("main").jooqConfiguration
        cfg.jdbc = org.jooq.meta.jaxb.Jdbc().apply {
            driver = "org.postgresql.Driver"
            url = jdbcUrl
            user = "postgres"
            password = "postgres"
        }
    }
    doLast {
        codegenPg.close()
    }
}
