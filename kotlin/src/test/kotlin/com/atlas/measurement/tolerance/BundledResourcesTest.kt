package com.atlas.measurement.tolerance

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The artifact carries every released profile and a manifest (version, sha256 per file, toleranceProfileVersion). */
class BundledResourcesTest {
    private val mapper = ObjectMapper()
    private val repoTolerance = Paths.get("..", "tolerance")

    private fun resource(path: String): ByteArray =
        BundledResourcesTest::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: throw AssertionError("missing resource $path")

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun theBundledProfileIsTheRepositoryFileAndIsSelectableByVersion() {
        assertEquals(listOf("1.0.0"), ToleranceProfile.bundledVersions())
        assertEquals("1.0.0", ToleranceProfile.bundled("1.0.0").version)
        assertArrayEquals(Files.readAllBytes(repoTolerance.resolve("1.0.0/tolerance-profile.json")), resource("/tolerance/1.0.0/tolerance-profile.json"))
        assertThrows(IllegalArgumentException::class.java) { ToleranceProfile.bundled("9.9.9") }
        assertThrows(IllegalArgumentException::class.java) { ToleranceProfile.bundled("../1.0.0") }
    }

    @Test
    fun theBundledFilesMatchTheManifestChecksums() {
        val manifest = mapper.readTree(resource("/tolerance/manifest.json"))
        assertEquals("tolerance", manifest.get("manifest").asText())
        assertEquals("1.0.0", manifest.get("toleranceProfileVersion").asText())
        // Maven filtering replaced the placeholder with the release version
        assertFalse(manifest.get("version").asText().contains("@"))
        assertTrue(manifest.get("version").asText().isNotEmpty())
        for (f in manifest.get("files")) {
            val path = f.get("path").asText()
            assertEquals(f.get("sha256").asText(), sha256(resource("/tolerance/$path")), path)
        }
    }
}
