import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    java
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "dev.dotktr"
version = "0.1.1-dev"
val riderPath = providers.gradleProperty("riderPath").orElse(providers.environmentVariable("RIDER_HOME"))
    .orElse("${System.getProperty("user.home")}/Applications/Rider.app/Contents")
val ideaPath = providers.gradleProperty("ideaPath").orElse(providers.environmentVariable("IDEA_HOME"))
    .orElse("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app/Contents")
val dotnet = providers.gradleProperty("dotnetPath").orElse(providers.environmentVariable("DOTNET_EXE")).orElse("dotnet")

repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
    // Rider 262 exposes APIs in additional product modules that this Gradle plugin
    // version does not yet include in its automatic platform compile classpath.
    compileOnly(fileTree("${riderPath.get()}/lib") { include("*.jar") })
    intellijPlatform {
        local(riderPath)
        localPlugin(ideaPath.map { "$it/plugins/Kotlin" })
        localPlugin(ideaPath.map { "$it/plugins/java" })
    }
    testImplementation(kotlin("test"))
}
sourceSets {
    main {
        kotlin.srcDirs("src/rider/main/kotlin", "third_party/dotkt/src")
        resources.srcDir("src/rider/main/resources")
    }
    test { kotlin.srcDir("src/rider/test/kotlin") }
}
val integrationTest by sourceSets.creating {
    kotlin.srcDir("src/rider/integrationTest/kotlin")
    resources.srcDir("src/rider/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
tasks.register<Jar>("integrationDriverJar") {
    archiveClassifier.set("integration-driver")
    dependsOn(integrationTest.classesTaskName)
    from(integrationTest.output.classesDirs)
    // The platform plugin patches descriptors in ProcessResources tasks. This driver
    // deliberately has its own ID and must use the unpatched test-only descriptor.
    from("src/rider/integrationTest/resources")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21); freeCompilerArgs.add("-Xcontext-parameters") } }

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        name = "DotKtR"
        version = project.version.toString()
        ideaVersion { sinceBuild = "262.10968"; untilBuild = "262.10968.*" }
    }
}

val compileDotNet by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/dotnet/DotKtR.Backend") { exclude("bin/**", "obj/**") }, "src/dotnet/Directory.Build.props")
    outputs.dir(layout.buildDirectory.dir("dotnet"))
    commandLine(dotnet.get(), "build", "src/dotnet/DotKtR.Backend/DotKtR.Backend.csproj", "-c", "Release", "--nologo",
        "-v:minimal", "-p:UseSharedCompilation=false", "-p:RiderHost=${riderPath.get()}/lib/ReSharperHost",
        "-o", layout.buildDirectory.dir("dotnet").get().asFile.absolutePath)
}
tasks.processResources {
    from("third_party/dotkt/LICENSE") { into("META-INF/licenses"); rename { "dotkt-APACHE-2.0.txt" } }
    from("third_party/dotkt/NOTICE") { into("META-INF/licenses"); rename { "dotkt-NOTICE.txt" } }
}
tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(compileDotNet)
    from(layout.buildDirectory.dir("dotnet")) { include("DotKtR.Backend.dll", "DotKtR.Backend.pdb"); into("DotKtR/dotnet") }
    doLast {
        project.exec {
            commandLine("python3", "tools/prepare_compat_host.py", "--idea", ideaPath.get(), "--rider", riderPath.get(),
                "--plugins", defaultDestinationDirectory.get().asFile.absolutePath)
        }
    }
}
tasks.runIde {
    maxHeapSize = "2048m"
    jvmArgs("-Dkotlin.index.non.source.roots=true")
    providers.gradleProperty("solution").orNull?.let { args(it) }
}
tasks.wrapper {
    gradleVersion = "8.14.4"
    distributionType = Wrapper.DistributionType.BIN
}
tasks.verifyPluginStructure { ignoreWarnings = false }
