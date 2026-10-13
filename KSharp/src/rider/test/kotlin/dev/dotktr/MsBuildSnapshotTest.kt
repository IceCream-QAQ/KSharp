package dev.dotktr

import com.google.gson.JsonParser
import com.google.gson.Gson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MsBuildSnapshotTest {
    @Test fun absoluteStdlibPathIsOneLibraryNotPathSegments() {
        val root = Files.createTempDirectory("dotktr-snapshot-")
        try {
            val source = Files.writeString(root.resolve("Program.kt"), "fun main() {}")
            val stdlib = Files.writeString(root.resolve("stdlib.klib"), "fixture")
            val reference = Files.writeString(root.resolve("library.klib"), "fixture")
            val data = Gson().toJsonTree(mapOf(
                "Properties" to mapOf("TargetFramework" to "net10.0", "DotKtKotlinVersion" to "2.4.10", "DotKtStdlib" to stdlib.toString()),
                "Items" to mapOf("KotlinCompile" to listOf(mapOf("FullPath" to source.toString())),
                    "DotKtResolvedKlibReference" to listOf(mapOf("Identity" to reference.toString(), "SourceAssembly" to root.resolve("library.dll").toString())))
            )).asJsonObject
            val snapshot = MsBuildSnapshot.parse(root.resolve("App.ktproj"), "net10.0", data)
            assertEquals(listOf(reference, stdlib), snapshot.libraries)
        } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    @Test fun singleTargetAndDuplicateInnerBuildAreUnambiguous() {
        val properties = JsonParser.parseString("""{"TargetFramework":"net10.0","TargetFrameworks":"net10.0"}""").asJsonObject
        assertEquals("net10.0", MsBuildSnapshot.chooseFramework(properties, ""))
    }
    @Test fun multipleTargetsRequireExplicitSelection() {
        val properties = JsonParser.parseString("""{"TargetFramework":"","TargetFrameworks":"net9.0;net10.0"}""").asJsonObject
        assertFailsWith<IllegalArgumentException> { MsBuildSnapshot.chooseFramework(properties, "") }
        assertEquals("net10.0", MsBuildSnapshot.chooseFramework(properties, "net10.0"))
        assertFailsWith<IllegalArgumentException> { MsBuildSnapshot.chooseFramework(properties, "net8.0") }
    }
    @Test fun frameworkCannotInjectAdditionalMsBuildProperties() {
        val properties = JsonParser.parseString("""{"TargetFramework":"net10.0;Other=1"}""").asJsonObject
        assertFailsWith<IllegalArgumentException> { MsBuildSnapshot.chooseFramework(properties, "Other=1") }
    }
    @Test fun ignoresBuildPreambleButRequiresMetadata() {
        assertEquals("ok", MsBuildSnapshot.json("restore complete\n{\"status\":\"ok\"}")["status"].asString)
        assertFailsWith<IllegalArgumentException> { MsBuildSnapshot.json("MSBuild target not found") }
    }
}
