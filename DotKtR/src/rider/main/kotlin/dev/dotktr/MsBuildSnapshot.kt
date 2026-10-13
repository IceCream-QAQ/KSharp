package dev.dotktr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

data class MsBuildSnapshot(val projectFile: Path, val framework: String, val kotlinVersion: String,
                          val sources: List<Path>, val libraries: List<Path>, val assemblies: Map<String, String>,
                          val runtimeIdentifier: String = "", val assemblyIdentities: Map<String, String> = emptyMap(),
                          val inputKey: String = "", val watchedFiles: Set<String> = emptySet()) {
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
                require(values.size == 1) { "Choose a target framework in Settings | Languages & Frameworks | KSharp." }
                values.single()
            }
            require(selected in values && selected.matches(Regex("[A-Za-z0-9_.-]+"))) { "Unsupported target framework: $selected" }
            return selected
        }

        fun parse(project: Path, framework: String, data: JsonObject): MsBuildSnapshot {
            val properties = data.getAsJsonObject("Properties") ?: error("MSBuild did not return Properties.")
            val version = properties["DotKtKotlinVersion"]?.asString.orEmpty()
            require(version == "2.4.10") { "KSharp 0.1 requires DotKt Kotlin 2.4.10; the project reports '$version'." }
            require(properties["TargetFramework"]?.asString == framework) { "MSBuild returned a different target framework." }
            val items = data.getAsJsonObject("Items") ?: error("MSBuild did not return Items.")
            val sources = items.getAsJsonArray("KotlinCompile")?.map {
                Path.of(it.asJsonObject["FullPath"].asString).toAbsolutePath().normalize()
            }?.distinct().orEmpty()
            require(sources.isNotEmpty()) { "No KotlinCompile sources were found." }
            require(sources.all { it.startsWith(project.parent) && Files.isRegularFile(it) }) {
                "KSharp 0.1 requires Kotlin source files under the .ktproj directory; linked sources are not supported yet."
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
            require(libraries.all { Files.isRegularFile(it) && it.toString().endsWith(".klib") }) { "A resolved KLIB is missing. Restore/build the project and refresh KSharp." }
            return MsBuildSnapshot(project, framework, version, sources, libraries, assemblies,
                properties["RuntimeIdentifier"]?.asString.orEmpty())
        }
    }
}

/** Read-only projection inputs. Source contents are deliberately not a KLIB cache input. */
data class ProjectionInputs(val projectFile: Path, val framework: String, val runtimeIdentifier: String,
                            val kotlinVersion: String, val sources: List<Path>, val references: List<Path>,
                            val stdlib: Path, val projector: Path) {
    companion object {
        fun parse(project: Path, framework: String, data: JsonObject): ProjectionInputs {
            val properties = data.getAsJsonObject("Properties") ?: error("MSBuild did not return Properties.")
            val version = properties["DotKtKotlinVersion"]?.asString.orEmpty()
            require(version == "2.4.10") { "KSharp requires DotKt Kotlin 2.4.10; the project reports '$version'." }
            require(properties["TargetFramework"]?.asString == framework) { "MSBuild returned a different target framework." }
            val items = data.getAsJsonObject("Items") ?: error("MSBuild did not return Items.")
            fun paths(name: String) = items.getAsJsonArray(name)?.map {
                val item = it.asJsonObject
                Path.of((item["FullPath"] ?: item["Identity"]).asString).toAbsolutePath().normalize()
            }.orEmpty()
            val sources = paths("KotlinCompile").distinct()
            require(sources.isNotEmpty() && sources.all { it.startsWith(project.parent) && Files.isRegularFile(it) }) {
                "KSharp requires existing KotlinCompile sources under the .ktproj directory."
            }
            // Include both reference/implementation universes, including the stdlib twins:
            // dll2klib's delegate/type-reference catalog can depend on other assemblies.
            val references = (paths("ReferencePathWithRefAssemblies") + paths("ReferencePath"))
                .filter { it.toString().endsWith(".dll", true) }.distinct().sortedBy { it.toString() }
            require(references.isNotEmpty() && references.all(Files::isRegularFile)) { "A referenced DLL is missing; build references and refresh KLIB." }
            fun file(property: String): Path {
                val value = properties[property]?.asString.orEmpty()
                require(value.isNotBlank()) { "$property is not configured." }
                return Path.of(value).toAbsolutePath().normalize().also { require(Files.isRegularFile(it)) { "$property is missing: $it" } }
            }
            return ProjectionInputs(project, framework, properties["RuntimeIdentifier"]?.asString.orEmpty(), version,
                sources, references, file("DotKtStdlib"), file("DotKtDll2Klib"))
        }
    }
}
