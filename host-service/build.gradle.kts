plugins { application }
group = "dev.monocle"
version = providers.gradleProperty("mod_version").get()
base { archivesName.set("monocle-host") }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
dependencies { implementation(project(":coordinator-core")) }
application {
    mainClass.set("dev.monocle.host.Main")
    applicationName = "monocle-host"
}
distributions { main { distributionBaseName.set("monocle-host"); contents { from("README.md"); from(rootProject.file("LICENSE")) } } }
val hostServiceCheck = tasks.register<JavaExec>("hostServiceCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.monocle.host.HostServiceTest")
    enableAssertions = true
}
tasks.test { exclude("**/HostServiceTest*.class") }
tasks.check { dependsOn(hostServiceCheck) }
