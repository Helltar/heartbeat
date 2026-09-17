import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

description = "File-based liveness signal for a container healthcheck, fed by the loop it watches."

kotlin {
    jvmToolchain(21)
    explicitApi()

    // the stdlib a consumer is handed, which gradle would otherwise raise to this compiler's own
    coreLibrariesVersion = "2.2.0"

    // a consumer compiles against this with whatever kotlin and jdk it is on, so the library stays
    // behind its own toolchain instead of dragging everyone up to it. 11 is the floor the code
    // needs (`Files.writeString`), and `-Xjdk-release` keeps newer jdk api from slipping in.
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

// there is no java here, but this is what the published metadata takes its minimum jvm from.
tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

java {
    withSourcesJar()
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.helltar.heartbeat")
    }
}

dependencies {
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("skipped", "failed")
    }
}

// central requires a javadoc artifact. dokka's javadoc format is still alpha, so the html
// publication ships in that jar instead: the validator checks that the file is there, people read
// what is inside it.
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications.register<MavenPublication>("library") {
        from(components["java"])
        artifact(javadocJar)

        pom {
            name = "Heartbeat"
            description = project.description
            url = "https://github.com/Helltar/heartbeat"

            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/license/mit"
                }
            }

            developers {
                developer {
                    id = "Helltar"
                    name = "Helltar"
                    url = "https://helltar.com"
                }
            }

            scm {
                connection = "scm:git:https://github.com/Helltar/heartbeat.git"
                developerConnection = "scm:git:ssh://git@github.com/Helltar/heartbeat.git"
                url = "https://github.com/Helltar/heartbeat"
            }
        }
    }

    // the release workflow zips this directory whole. it is already the repository layout, with the
    // checksums beside every file, that a central portal bundle is.
    repositories.maven {
        name = "bundle"
        url = uri(layout.buildDirectory.dir("bundle"))
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
// hoisted: inside `signing { }` the publishing accessor resolves against the wrong receiver.
val publications = publishing.publications

// an ordinary build signs nothing: the key exists only in the release workflow's environment, and a
// developer who has none still gets a bundle shaped like the published thing.
if (signingKey.isPresent && signingPassword.isPresent) {
    signing {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publications)
    }
}
