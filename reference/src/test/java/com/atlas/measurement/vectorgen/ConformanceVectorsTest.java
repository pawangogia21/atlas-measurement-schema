package com.atlas.measurement.vectorgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.measurement.algo.ConformancePipeline;
import com.atlas.measurement.algo.SceneInput;
import com.atlas.measurement.algo.SceneResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The committed vectors/conformance/1.0.0 must be exactly what the generator produces, and must replay. */
class ConformanceVectorsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path RELEASE = Paths.get("..", "vectors", "conformance", ConformanceVectorGenerator.VERSION);

    @Test
    void regenerationIsByteIdenticalToTheCommittedRelease(@TempDir Path tmp) throws IOException {
        ConformanceVectorGenerator.generate(tmp);
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path committed = RELEASE.resolve(tmp.relativize(p).toString());
                assertThat(committed).as(tmp.relativize(p).toString()).exists();
                assertThat(ConformanceVectorGenerator.sha256(Files.readAllBytes(committed))).as(tmp.relativize(p).toString())
                        .isEqualTo(ConformanceVectorGenerator.sha256(Files.readAllBytes(p)));
            }
        }
        try (Stream<Path> s = Files.walk(RELEASE)) {
            assertThat(s.filter(Files::isRegularFile).count()).isEqualTo(Files.walk(tmp).filter(Files::isRegularFile).count());
        }
    }

    @Test
    void manifestHashesMatchTheFilesOnDisk() throws IOException {
        JsonNode manifest = MAPPER.readTree(RELEASE.resolve("manifest.json").toFile());
        assertThat(manifest.get("version").asText()).isEqualTo("1.0.0");
        for (JsonNode f : manifest.get("files")) {
            byte[] data = Files.readAllBytes(RELEASE.resolve(f.get("path").asText()));
            assertThat(ConformanceVectorGenerator.sha256(data)).as(f.get("path").asText()).isEqualTo(f.get("sha256").asText());
            assertThat(data.length).isEqualTo(f.get("bytes").asInt());
        }
    }

    @Test
    void referencePipelineReproducesEveryCommittedSceneWithinTheManifestTolerances() throws IOException {
        JsonNode manifest = MAPPER.readTree(RELEASE.resolve("manifest.json").toFile());
        int scenes = 0;
        for (JsonNode v : manifest.get("vectors")) {
            if (!v.get("kind").asText().equals("scene")) {
                continue;
            }
            scenes++;
            Path inPath = RELEASE.resolve(v.get("input").asText());
            JsonNode in = MAPPER.readTree(inPath.toFile());
            JsonNode expected = MAPPER.readTree(RELEASE.resolve(v.get("expected").asText()).toFile());
            SceneResult r = ConformancePipeline.run(load(inPath.getParent(), in));
            String id = v.get("id").asText();
            assertThat(r.status.name()).as(id).isEqualTo(expected.get("status").asText());
            assertThat(r.validPixels).as(id).isEqualTo(expected.get("counts").get("validPixels").asInt());
            assertThat(r.supportPlaneInliers).as(id).isEqualTo(expected.get("counts").get("supportPlaneInliers").asInt());
            assertThat(r.objectPoints).as(id).isEqualTo(expected.get("counts").get("objectPoints").asInt());
            if (r.status == SceneResult.Status.OK) {
                double m = v.get("tolerances").get("dimensions.lengthM").get("value").asDouble();
                assertThat(r.lengthM).as(id).isEqualTo(expected.get("dimensions").get("lengthM").asDouble(), org.assertj.core.api.Assertions.offset(m));
                assertThat(r.heightM).as(id).isEqualTo(expected.get("dimensions").get("heightM").asDouble(), org.assertj.core.api.Assertions.offset(m));
            }
        }
        assertThat(scenes).isEqualTo(14);
    }

    @Test
    void noiseFreeScenesRecoverTheAnalyticBoxWithinTheGridResolution() throws IOException {
        for (String id : new String[] {"S02-box-60x40x30-128x96", "S04-box-100x50x60-128x96"}) {
            JsonNode e = MAPPER.readTree(RELEASE.resolve("scenes/" + id + "/expected.json").toFile());
            JsonNode t = e.get("truth");
            assertThat(e.get("dimensions").get("lengthM").asDouble()).isBetween(t.get("lengthM").asDouble() - 0.01, t.get("lengthM").asDouble() + 0.01);
            assertThat(e.get("dimensions").get("widthM").asDouble()).isBetween(t.get("widthM").asDouble() - 0.01, t.get("widthM").asDouble() + 0.01);
            assertThat(e.get("dimensions").get("heightM").asDouble()).isBetween(t.get("heightM").asDouble() - 1e-4, t.get("heightM").asDouble() + 1e-4);
        }
    }

    @Test
    void degenerateScenesHitTheirDocumentedStatuses() throws IOException {
        assertThat(status("D01-floor-only")).isEqualTo("NO_OBJECT");
        assertThat(status("D02-all-invalid-depth")).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(status("D03-low-confidence")).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(status("D04-sparse-30-pixels")).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(status("D05-wall-only")).isEqualTo("NO_SUPPORT_PLANE");
    }

    private static String status(String id) throws IOException {
        return MAPPER.readTree(RELEASE.resolve("scenes/" + id + "/expected.json").toFile()).get("status").asText();
    }

    /** Reads the file formats exactly as a Kit would (independent of the generator's in-memory objects). */
    private static SceneInput load(Path dir, JsonNode in) throws IOException {
        int w = in.get("width").asInt();
        int h = in.get("height").asInt();
        float[] depth = readFloats(dir.resolve(in.get("depthFile").asText()), w * h);
        float[] conf = new float[w * h];
        JsonNode c = in.get("confidence");
        if (c.has("file")) {
            conf = readFloats(dir.resolve(c.get("file").asText()), w * h);
        } else {
            java.util.Arrays.fill(conf, (float) c.get("constant").asDouble());
        }
        JsonNode i = in.get("depthIntrinsics");
        double[] pose = new double[16];
        for (int k = 0; k < 16; k++) {
            pose[k] = in.get("poseCameraToWorldRowMajor").get(k).asDouble();
        }
        return new SceneInput(w, h, i.get("fx").asDouble(), i.get("fy").asDouble(), i.get("cx").asDouble(),
                i.get("cy").asDouble(), pose, in.get("windowIndex").asLong(), depth, conf);
    }

    private static float[] readFloats(Path p, int count) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.remaining()).isEqualTo(count * 4);
        float[] f = new float[count];
        for (int i = 0; i < count; i++) {
            f[i] = b.getFloat();
        }
        return f;
    }
}
