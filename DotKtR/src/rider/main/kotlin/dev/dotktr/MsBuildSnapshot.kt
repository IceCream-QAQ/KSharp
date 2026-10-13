package dev.dotktr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

data class MsBuildSnapshot(val projectFile: Path, val framework: String, val kotlinVersion: String,
                          val sources: List<Path>, val libraries: List<Path>, val assemblies: Map<String, String>) {
    companion object {
        fun json(output: String): JsonObject {
            val start = output.indexOf('{')
            require(start >= 0) { "MSBuild did not return project metadata. Check that the project imports the DotKt SDK." }
            return JsonParser.parseString(output.substring(start)).asJsonObject
        }

        fun chooseFramework(properties: JsonObject, requested: String): String {
            val values = sequenceOf("TargetFramework", "TargetFrameworks")
                .flatMap { properties[it]?.asString.orEmpty().split(';').asSequence() }
                .filter { it.isNotBlank() }.distinct().toList()
            require(values.isNotEmpty()) { "The .ktproj has no target framework." }
            val selected = requested.ifEmpty {
                require(values.size == 1) { "Choose a target framework in Settings | Languages & Frameworks | DotKt." }
                values.single()
            }
            require(selected in values && selected.matches(Regex("[A-Za-z0-9_.-]+"))) { "Unsupported target framework: $selected" }
            return selected
        }

        fun parse(project: Path, framework: String, data: JsonObject): MsBuildSnapshot {
            val properties = data.getAsJsonObject("Properties") ?: error("MSBuild did not return Properties.")
            val version = properties["DotKtKotlinVersion"]?.asString.orEmpty()
            require(version == "2.4.10") { "DotKtR 0.1 requires DotKt Kotlin 2.4.10; the project reports '$version'." }
            require(properties["TargetFramework"]?.asString == framework) { "MSBuild returned a different target framework." }
            val items = data.getAsJsonObject("Items") ?: error("MSBuild did not return Items.")
            val sources = items.getAsJsonArray("KotlinCompile")?.map {
                Path.of(it.asJsonObject["FullPath"].asString).toAbsolutePath().normalize()
            }?.distinct().orEmpty()
            require(sources.isNotEmpty()) { "No KotlinCompile sources were found." }
            require(sources.all { it.startsWith(project.parent) && Files.isRegularFile(it) }) {
                "DotKtR 0.1 requires Kotlin source files under the .ktproj directory; linked sources are not supported yet."
            }
            val references = items.getAsJsonArray("DotKtResolvedKlibReference") ?: error("No DotKtResolvedKlibReference items were returned.")
            val assemblies = references.associate {
                val entry = it.asJsonObject
                Path.of(entry["Identity"].asString).toAbsolutePath().normalize().toString() to
                    Path.of(entry["SourceAssembly"].asString).toAbsolutePath().normalize().toString()
            }
            val stdlib = properties["DotKtStdlib"]?.asString.orEmpty()
            require(stdlib.isNotBlank()) { "DotKtStdlib is not configured." }
            val libraries = (assemblies.keys.map(Path::of) + listOf(Path.of(stdlib).toAbsolutePath().normalize())).distinct()
            require(libraries.all { Files.isRegularFile(it) && it.toString().endsWith(".klib") }) { "A resolved KLIB is missing. Restore/build the project and refresh DotKt." }
            return MsBuildSnapshot(project, framework, version, sources, libraries, assemblies)
        }
    }
}
