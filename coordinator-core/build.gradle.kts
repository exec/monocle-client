plugins { `java-library` }

group = "dev.monocle"
version = providers.gradleProperty("mod_version").get()
base { archivesName.set("monocle-coordinator-core") }
repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}

dependencies {
    api("com.google.code.gson:gson:2.14.0")
    implementation("org.luaj:luaj-jse:3.0.1")
}

val coordinatorCoreCheck = tasks.register<JavaExec>("coordinatorCoreCheck") {
    group = "verification"
    description = "Runs shared coordinator decisions and Lua with no Minecraft, Fabric, window or network."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.monocle.coordinator.CoordinatorCoreTest")
    enableAssertions = true
}
tasks.test { exclude("**/CoordinatorCoreTest*.class") } // Assertion main above, not a test-framework test.
tasks.check { dependsOn(coordinatorCoreCheck) }
tasks.withType<Jar>().configureEach { from(rootProject.file("LICENSE")) }
