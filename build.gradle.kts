plugins { java }

repositories { mavenCentral() }

val coreJar = providers.gradleProperty("studentDatabaseJar").orNull
dependencies {
    compileOnly(if (coreJar == null) "igs-landstuhl:student-database:v2.0.0-SNAPSHOT-3" else files(coreJar))
    compileOnly("org.slf4j:slf4j-api:2.0.13")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.withType<Jar> { manifest { attributes["Implementation-Title"] = "Freistellung"; attributes["Implementation-Version"] = project.version } }
tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}
version = "0.1.0-dev"
group = "igs-landstuhl.plugins"
