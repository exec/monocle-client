import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = providers.gradleProperty("archives_base_name").get()
val mavenGroup = providers.gradleProperty("maven_group").get()

base {
    archivesName = archivesBaseName
    group = mavenGroup
    version = providers.gradleProperty("mod_version").get()
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        name = "ViaVersion"
        url = uri("https://repo.viaversion.com")
    }
    mavenCentral()

    exclusiveContent {
        forRepository {
            maven {
                name = "modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

val modInclude = configurations.create("modInclude")
val jij = configurations.create("jij")
val launcher = sourceSets.create("launcher") {
    java.srcDir("src/launcher/java")
}

configurations {
    // include mods
    implementation.configure {
        extendsFrom(modInclude)
    }
    include.configure {
        extendsFrom(modInclude)
    }

    // include libraries (jar-in-jar)
    implementation.configure {
        extendsFrom(jij)
    }
    include.configure {
        extendsFrom(jij)
    }
}

dependencies {
    // The client and future standalone host consume one compiled decision core.
    implementation(project(":coordinator-core"))
    include(project(":coordinator-core"))
    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    val fapiVersion = libs.versions.fabric.api.get()
    modInclude(fabricApi.module("fabric-api-base", fapiVersion))
    modInclude(fabricApi.module("fabric-resource-loader-v1", fapiVersion))

    // Compat fixes
    compileOnly(fabricApi.module("fabric-renderer-indigo", fapiVersion))
    compileOnly(libs.sodium) { isTransitive = false }
    compileOnly(libs.lithium) { isTransitive = false }
    compileOnly(libs.iris) { isTransitive = false }
    compileOnly(libs.viafabricplus) { isTransitive = false }
    compileOnly(libs.viafabricplus.api) { isTransitive = false }

    compileOnly(libs.baritone)
    compileOnly(libs.modmenu)

    // Optional Printer Helper integration: users install these mods separately.
    for (optionalMod in listOf("litematica:CuniXtbo", "malilib:KvjmGjAV", "litematica-printer:7l7ihnI0")) {
        compileOnly("maven.modrinth:$optionalMod") { isTransitive = false }
        // Reproducible optional-mod startup check; still never included in the release JAR.
        if (providers.gradleProperty("printerSmoke").isPresent)
            runtimeOnly("maven.modrinth:$optionalMod") { isTransitive = false }
    }

    // Libraries (JAR-in-JAR)
    jij("org.luaj:luaj-jse:3.0.1") { isTransitive = false }
    jij("org.java-websocket:Java-WebSocket:1.6.0") { isTransitive = false }
    jij(libs.orbit)
    jij(libs.starscript)
    jij(libs.discord.ipc)
    jij(libs.reflections)
    jij(libs.netty.handler.proxy) { isTransitive = false }
    jij(libs.netty.codec.socks) { isTransitive = false }
    jij(libs.waybackauthlib)
    jij(libs.minecraft.auth)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }

    if (System.getenv("CI")?.toBoolean() == true) {
        withSourcesJar()
        withJavadocJar()
    }
}

// Handle transitive dependencies for jar-in-jar
// Based on implementation from BaseProject by florianreuth/EnZaXD
// Source: https://github.com/florianreuth/BaseProject/blob/main/src/main/kotlin/de/florianreuth/baseproject/Fabric.kt
// Licensed under Apache License 2.0
val jijExcluded = setOf("org.slf4j", "jsr305")
listOf("api", "implementation", "include").forEach { configName ->
    configurations.named(configName).configure {
        // Must run even when include already contains the shared coordinator project.
        withDependencies {
            configurations.getByName("jij").incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .forEach { id ->
                    val notation = "${id.group}:${id.module}:${id.version}"
                    if (jijExcluded.none { notation.contains(it) }) {
                        add(project.dependencies.create(notation) {
                            isTransitive = false
                        })
                    }
                }
        }
    }
}

loom {
    accessWidenerPath = file("src/main/resources/monocle-client.classtweaker")
}

fun toMinecraftCompat(version: String): String {
    // Stable release
    val stable = Regex("""^(\d{2})\.([1-9]\d*)(?:\.(\d+))?$""")

    stable.matchEntire(version)?.let {
        val (year, drop, _) = it.destructured
        return "~$year.$drop"
    }

    // Prerelease
    val pre = Regex("""^(\d{2})\.([1-9]\d*)-pre[-.](\d+)$""")
    pre.matchEntire(version)?.let {
        return version.replace("-pre-", "-pre.")
    }

    // Release Candidate
    val rc = Regex("""^(\d{2})\.([1-9]\d*)-rc[-.](\d+)$""")
    rc.matchEntire(version)?.let {
        return version.replace("-rc-", "-rc.")
    }

    // fallback
    return version
}

tasks {
    withType<AbstractArchiveTask>().configureEach {
        archiveVersion.set("v${project.version}-${libs.versions.minecraft.get()}")
    }

    val bundledLibrariesCheck = register("bundledLibrariesCheck") {
        group = "verification"
        description = "Checks that release JARs embed and register every required library, including transitives."
        dependsOn(jar)
        doLast {
            ZipFile(jar.get().archiveFile.get().asFile).use { archive ->
                listOf(
                    "LICENSE_monocle",
                    "META-INF/licenses/Java-WebSocket-MIT.txt",
                    "META-INF/licenses/Quiettee-Utils-NOTICE.txt",
                    "assets/monocle-client/licenses/luaj-LICENSE.txt",
                    "assets/monocle-client/fonts/GlacialIndifference-OFL.txt",
                    "assets/monocle-client/fonts/GlacialIndifference-NOTICE.txt"
                ).forEach { path ->
                    val entry = archive.getEntry(path)
                    check(entry != null && entry.size > 0) { "Release JAR is missing required notice: $path" }
                }
                val metadata = archive.getInputStream(archive.getEntry("fabric.mod.json")).bufferedReader().use { it.readText() }
                jij.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                    val id = artifact.moduleVersion.id
                    if (jijExcluded.none { "${id.group}:${id.name}:${id.version}".contains(it) }) {
                        val path = "META-INF/jars/${artifact.file.name}"
                        check(archive.getEntry(path) != null && metadata.contains("\"$path\"")) {
                            "Release JAR is missing a registered bundled library: $path"
                        }
                    }
                }
            }
        }
    }

    val documentationCheck = register("documentationCheck") {
        group = "verification"
        description = "Checks local Markdown links in project documentation."
        inputs.files(fileTree(projectDir) {
            include("README.md", "CHANGELOG.md", "SECURITY.md", "docs/**/*.md", "host-service/README.md", "coordinator-core/README.md")
        })
        doLast {
            val link = Regex("""!?\[[^]]*]\(([^)]+)\)""")
            inputs.files.files.sorted().forEach { source ->
                link.findAll(source.readText()).forEach { match ->
                    val raw = match.groupValues[1].trim().substringBefore(" \"").removeSurrounding("<", ">")
                    if (!raw.startsWith("#") && !raw.startsWith("http://") && !raw.startsWith("https://") && !raw.startsWith("mailto:")) {
                        val target = source.parentFile.resolve(raw.substringBefore('#')).normalize()
                        check(target.isFile) { "Broken documentation link in ${source.relativeTo(projectDir)}: $raw" }
                    }
                }
            }
        }
    }

    val highwayBuilderCheck = register<JavaExec>("highwayBuilderCheck") {
        group = "verification"
        description = "Checks Highway Builder planning and recovery rules without starting Minecraft."
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.monocle.client.systems.modules.world.HighwayPlanTest")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor(java.toolchain))
        enableAssertions = true
    }

    val highwaySupplyCheck = register<JavaExec>("highwaySupplyCheck") {
        group = "verification"
        description = "Checks supply container recovery identity using Minecraft item components."
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.monocle.client.systems.modules.world.HighwaySupplyTest")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor(java.toolchain))
        enableAssertions = true
    }

    val monocleStyleCheck = register<JavaExec>("monocleStyleCheck") {
        group = "verification"
        description = "Checks Monocle theme color and animation helpers without starting Minecraft."
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.monocle.client.gui.themes.monocle.MonocleStyleTest")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor(java.toolchain))
        enableAssertions = true
    }

    val monocleFontCheck = register<JavaExec>("monocleFontCheck") {
        group = "verification"
        description = "Checks bundled fonts, native OpenType rasterization, and font licensing resources without a GPU."
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.monocle.client.renderer.MonocleFontTest")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor(java.toolchain))
        enableAssertions = true
    }

    val moduleChecks = mapOf(
        "botsCheck" to "dev.monocle.client.systems.bots.BotsTest",
        "swarmCrewCheck" to "dev.monocle.client.systems.modules.misc.swarm.SwarmCrewTest",
        "crewInventoryCheck" to "dev.monocle.client.systems.modules.misc.swarm.CrewInventoryTest",
        "encounterHistoryCheck" to "dev.monocle.client.utils.world.EncounterHistoryTest",
        "notificationFeedCheck" to "dev.monocle.client.utils.render.NotificationFeedTest",
        "autoReconnectCheck" to "dev.monocle.client.systems.modules.misc.AutoReconnectTest",
        "chatRoutingCheck" to "dev.monocle.client.utils.network.ChatRoutingTest",
        "ircCheck" to "dev.monocle.client.utils.network.IrcConnectionTest",
        "autoTotemCheck" to "dev.monocle.client.systems.modules.combat.AutoTotemTest",
        "chestSwapCheck" to "dev.monocle.client.systems.modules.player.ChestSwapTest",
        "elytraSettingsCheck" to "dev.monocle.client.systems.modules.movement.ElytraSettingsTest",
        "scaffoldCheck" to "dev.monocle.client.systems.modules.movement.ScaffoldTest",
        "moduleHandoffCheck" to "dev.monocle.client.systems.modules.ModuleHandoffTest",
        "surroundCheck" to "dev.monocle.client.systems.modules.combat.SurroundTest",
        "autoToolCheck" to "dev.monocle.client.systems.modules.player.AutoToolTest",
        "autoArmorCheck" to "dev.monocle.client.systems.modules.combat.AutoArmorTest",
        "autoMendCheck" to "dev.monocle.client.systems.modules.player.AutoMendTest",
        "autoEatCheck" to "dev.monocle.client.systems.modules.player.AutoEatTest",
        "autoLogCheck" to "dev.monocle.client.systems.modules.combat.AutoLogTest",
        "stashFinderCheck" to "dev.monocle.client.systems.modules.world.StashFinderTest",
        "inventoryLoadoutCheck" to "dev.monocle.client.utils.player.InventoryLoadoutTest",
        "inventoryTransferCheck" to "dev.monocle.client.utils.player.InventoryTransferTest",
        "inventoryManagerCheck" to "dev.monocle.client.systems.modules.misc.InventoryManagerTest",
        "inventoryManagerUiCheck" to "dev.monocle.client.gui.screens.InventoryManagerUiTest",
        "litematicExporterCheck" to "dev.monocle.client.utils.world.LitematicExporterTest",
        "schematicSelectorCheck" to "dev.monocle.client.systems.modules.world.SchematicSelectorTest",
        "highwayMobCheck" to "dev.monocle.client.systems.modules.world.HighwayMobTest",
        "printerHelperCheck" to "dev.monocle.client.systems.modules.world.PrinterHelperTest",
        "printerFlightCheck" to "dev.monocle.client.utils.world.PrinterFlightTest",
        "printerIntegrationCheck" to "dev.monocle.client.modintegration.PrinterIntegrationTest",
        "printerRestockCheck" to "dev.monocle.client.systems.modules.world.PrinterRestockTest",
        "playerProfileConcurrencyCheck" to "dev.monocle.client.mixin.PlayerProfileConcurrencyTest"
    ).map { (taskName, main) ->
        register<JavaExec>(taskName) {
            group = "verification"
            description = "Checks Monocle module behavior without starting Minecraft."
            dependsOn(testClasses)
            classpath = sourceSets["test"].runtimeClasspath
            mainClass.set(main)
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor(java.toolchain))
            enableAssertions = true
        }
    }

    test {
        exclude("**/BotsTest*.class", "**/BotJobsTest*.class", "**/BotWorkflowsTest*.class", "**/BotLuaTest*.class", "**/BotRuntimeTest*.class", "**/BotSchedulerTest*.class", "**/BotTaskDataTest*.class", "**/BotProfilesTest*.class", "**/BotActionsTest*.class", "**/BotStashHuntTest*.class", "**/WorkflowCodeBoxTest*.class") // Run by botsCheck.
        exclude("**/SwarmCrewTest*.class")
        exclude("**/CrewInventoryTest*.class") // Run by crewInventoryCheck.
        exclude("**/ChatRoutingTest*.class")
        exclude("**/AutoReconnectTest*.class")
        exclude("**/NotificationFeedTest*.class")
        exclude("**/EncounterHistoryTest*.class")
        exclude("**/IrcConnectionTest*.class")
        exclude("**/AutoTotemTest*.class")
        exclude("**/ChestSwapTest*.class")
        exclude("**/ElytraSettingsTest*.class")
        exclude("**/ScaffoldTest*.class")
        exclude("**/ModuleHandoffTest*.class")
        exclude("**/SurroundTest*.class")
        exclude("**/AutoToolTest*.class")
        exclude("**/AutoArmorTest*.class")
        exclude("**/AutoMendTest*.class")
        exclude("**/AutoEatTest*.class")
        exclude("**/AutoLogTest*.class")
        exclude("**/StashFinderTest*.class")
        // These assertion-based mains run through their JavaExec tasks, not a test framework.
        exclude("**/HighwayPlanTest*.class", "**/HighwaySupplyTest*.class", "**/HighwayFarmingTest*.class", "**/MonocleStyleTest*.class", "**/MonocleFontTest*.class")
        exclude("**/InventoryLoadoutTest*.class", "**/InventoryTransferTest*.class", "**/InventoryManagerTest*.class", "**/InventoryManagerUiTest*.class")
        exclude("**/LitematicExporterTest*.class", "**/SchematicSelectorTest*.class")
        exclude("**/HighwayMobTest*.class")
        exclude("**/Printer*Test*.class")
        exclude("**/PlayerProfileConcurrencyTest*.class")
    }

    check {
        dependsOn(bundledLibrariesCheck, documentationCheck)
        dependsOn(":coordinator-core:check", ":host-service:check")
        dependsOn(highwayBuilderCheck, highwaySupplyCheck, monocleStyleCheck, monocleFontCheck)
        dependsOn(moduleChecks)
    }

    processResources {
        val buildNumber = providers.gradleProperty("build_number").getOrElse("")
        val commit = providers.gradleProperty("commit").getOrElse("")

        val propertyMap = mapOf(
            "version" to project.version,
            "build_number" to buildNumber,
            "commit" to commit,
            "jdk_version" to libs.versions.jdk.get(),
            "minecraft_version" to toMinecraftCompat(libs.versions.minecraft.get()),
            "loader_version" to libs.versions.fabric.loader.get()
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    // Compile launcher with Java 8 for backwards compatibility
    named<JavaCompile>("compileLauncherJava").configure {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
        options.compilerArgs.add("-Xlint:-options")
    }

    jar {
        inputs.property("archivesName", archivesBaseName)
        from("licenses/Java-WebSocket-MIT.txt") { into("META-INF/licenses") }
        from("licenses/Quiettee-Utils-NOTICE.txt") { into("META-INF/licenses") }

        from("LICENSE") {
            rename { "${it}_$archivesBaseName" }
        }

        // Include launcher classes
        from(launcher.output)

        manifest {
            attributes["Main-Class"] = "dev.monocle.client.Main"
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }

    javadoc {
        with(options as StandardJavadocDocletOptions) {
            addStringOption("Xdoclint:none", "-quiet")
            addStringOption("encoding", "UTF-8")
            addStringOption("charSet", "UTF-8")
        }
    }

    build {
        if (System.getenv("CI")?.toBoolean() == true) {
            dependsOn("javadocJar")
        }
    }
}
