import java.util.zip.ZipFile

plugins { application }
group = "dev.monocle"
version = providers.gradleProperty("mod_version").get()
base { archivesName.set("monocle-host") }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
dependencies { implementation(project(":coordinator-core")) }
tasks.processResources {
    from(rootProject.file("src/main/resources/assets/monocle-client/fonts")) {
        include("GlacialIndifference-Regular.otf", "GlacialIndifference-OFL.txt", "GlacialIndifference-NOTICE.txt")
        into("webui")
    }
}
application {
    mainClass.set("dev.monocle.host.Main")
    applicationName = "monocle-host"
}
distributions { main { distributionBaseName.set("monocle-host"); contents {
    from("README.md"); from(rootProject.file("LICENSE"))
    from(rootProject.file("docs/bot-web-transport.md"))
    from(rootProject.file("docs/bot-webui.md"))
    from(rootProject.file("licenses/Java-WebSocket-MIT.txt")) { into("licenses") }
} } }
val hostServiceCheck = tasks.register<JavaExec>("hostServiceCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.monocle.host.HostServiceTest")
    enableAssertions = true
}
tasks.test { exclude("**/HostServiceTest*.class", "**/TlsProxy*.class", "**/WebUiTest*.class") }
tasks.check { dependsOn(hostServiceCheck) }
val distributionContentsCheck = tasks.register("distributionContentsCheck") {
    group = "verification"
    description = "Checks the standalone-host distribution includes its documentation and licenses."
    dependsOn(tasks.distZip)
    doLast {
        ZipFile(tasks.distZip.get().archiveFile.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
            listOf("/README.md", "/LICENSE", "/licenses/Java-WebSocket-MIT.txt").forEach { suffix ->
                check(entries.any { it.name.endsWith(suffix) && it.size > 0 }) { "Host distribution is missing $suffix" }
            }
        }
    }
}
tasks.check { dependsOn(distributionContentsCheck) }
tasks.register<JavaExec>("webUiPreview") {
    group = "verification"
    description = "Runs the bundled UI with simulated workers and isolated test journals; Ctrl+C stops it."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.monocle.host.HostServiceTest")
    args("--webui")
    enableAssertions = true
}
