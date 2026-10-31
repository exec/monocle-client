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
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
}

val coordinatorCoreCheck = tasks.register<JavaExec>("coordinatorCoreCheck") {
    group = "verification"
    description = "Runs shared coordinator decisions, Lua and local-only notification HTTP checks without Minecraft or Fabric."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.monocle.coordinator.CoordinatorCoreTest")
    enableAssertions = true
}
tasks.test { exclude("**/CoordinatorCoreTest*.class", "**/ActionProgressTest*.class", "**/CommandReceiptsTest*.class", "**/DiscordNotificationsTest*.class", "**/IncidentTimelineTest*.class", "**/KnownHostilesTest*.class", "**/HostileWarningsTest*.class", "**/CrewTelemetryTest*.class", "**/OperationsLibraryTest*.class", "**/StashTransferTeamCheck*.class", "**/StashCatalogShouldCheck*.class") } // Assertion mains above, not test-framework tests.
tasks.test { exclude("**/KitCategoriesTest*.class") }
tasks.test { exclude("**/WorkerSupplyStationsTest*.class") }
tasks.test { exclude("**/StashSortPlannerTest*.class") }
tasks.test { exclude("**/StashSortExecutionTest*.class") }
tasks.test { exclude("**/EnderTransportCheck*.class") }
tasks.test { exclude("**/TaskJournalCheck*.class") }
tasks.check { dependsOn(coordinatorCoreCheck) }
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("licenses/Java-WebSocket-MIT.txt")) { into("META-INF/licenses") }
    from(listOf(rootProject.file("licenses/SQLite-JDBC-Apache-2.0.txt"), rootProject.file("licenses/SQLite-JDBC-Zentus.txt"))) { into("META-INF/licenses") }
}

tasks.test { exclude("**/ServerAliasesTest*.class") }
