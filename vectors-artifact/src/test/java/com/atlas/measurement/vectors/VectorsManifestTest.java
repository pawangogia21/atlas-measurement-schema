package com.atlas.measurement.vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

/** What a Kit sees when it adds this artifact as a test dependency: every file matches the checksums of the manifests. */
class VectorsManifestTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = VectorsManifestTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void theReleaseManifestCoversEverySetAndEverySetManifestCoversItsFiles() throws Exception {
        JsonNode manifest = MAPPER.readTree(resource("/vectors/manifest.json"));
        assertEquals("vectors", manifest.get("manifest").asText());
        assertFalse(manifest.get("version").asText().contains("@"), "Maven filtering replaces the version placeholder");
        assertEquals("1.0.0", manifest.get("toleranceProfileVersion").asText());
        assertEquals(3, manifest.get("files").size());
        int checked = 0;
        for (JsonNode f : manifest.get("files")) {
            String path = f.get("path").asText();
            byte[] setManifestBytes = resource("/vectors/" + path);
            assertEquals(f.get("sha256").asText(), sha256(setManifestBytes), path);
            String dir = path.substring(0, path.lastIndexOf('/') + 1);
            for (JsonNode file : MAPPER.readTree(setManifestBytes).get("files")) {
                assertEquals(file.get("sha256").asText(), sha256(resource("/vectors/" + dir + file.get("path").asText())), dir + file.get("path").asText());
                checked++;
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    void theProfileTheVectorsWereReleasedAgainstIsBundledToo() throws Exception {
        assertNotNull(VectorsManifestTest.class.getResource("/tolerance/1.0.0/tolerance-profile.json"));
        JsonNode tolerance = MAPPER.readTree(resource("/tolerance/manifest.json"));
        for (JsonNode f : tolerance.get("files")) {
            assertEquals(f.get("sha256").asText(), sha256(resource("/tolerance/" + f.get("path").asText())), f.get("path").asText());
        }
    }
}
