subprojects {
    group = "ai.pipestream.email"
    version = "0.1.0-SNAPSHOT"

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                showStackTraces = true
            }
        }
    }
}
