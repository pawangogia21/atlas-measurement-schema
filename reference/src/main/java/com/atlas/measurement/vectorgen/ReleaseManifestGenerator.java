package com.atlas.measurement.vectorgen;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates the two release manifests that travel inside every published artifact (AT-16 F9, section 35.5):
 * {@code tolerance/manifest.json} (carried by the Kotlin artifact and the Swift {@code AtlasMeasurementTolerance}
 * resources) and {@code vectors/manifest.json} (carried by the vector artifact and the Swift
 * {@code AtlasMeasurementVectors} resources). Each holds the release {@code version}, the
 * {@code toleranceProfileVersion} and the sha256 of every file the artifact bundles, so a Kit verifies what it
 * received. The vectors manifest lists each vector set's own manifest, which in turn lists the set's files.
 *
 * <p>{@code version} is written as the placeholder {@value #VERSION_PLACEHOLDER}: Maven resource filtering (Kotlin,
 * vector artifact) and the Swift assembly script replace it with the release version from the tag, so the committed
 * file (and its governance digest) does not change from release to release.
 * Usage: {@code ReleaseManifestGenerator <repoRoot> <outDir>} writes {@code <outDir>/tolerance/manifest.json} and
 * {@code <outDir>/vectors/manifest.json}; the committed copies live in the repository's tolerance/ and vectors/.
 */
public final class ReleaseManifestGenerator {
    public static final String VERSION_PLACEHOLDER = "@project.version@";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final String[] VECTOR_SETS = {"boundary", "conformance", "negative"};

    private static final Comparator<String> SEMVER = (a, b) -> {
        List<Integer> x = semver(a);
        List<Integer> y = semver(b);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(x.get(i), y.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    private ReleaseManifestGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: ReleaseManifestGenerator <repoRoot> <outDir>");
            System.exit(2);
        }
        generate(Path.of(args[0]), Path.of(args[1]));
    }

    public static void generate(Path repoRoot, Path outDir) throws IOException {
        Path tolerance = repoRoot.resolve("tolerance");
        List<String> versions;
        try (Stream<Path> s = Files.list(tolerance)) {
            versions = s.filter(p -> Files.isRegularFile(p.resolve("tolerance-profile.json")))
                    .map(p -> p.getFileName().toString()).sorted(SEMVER).collect(Collectors.toList());
        }
        if (versions.isEmpty()) {
            throw new IOException("no tolerance/<version>/tolerance-profile.json");
        }
        List<String> toleranceFiles = new ArrayList<>();
        for (String v : versions) {
            toleranceFiles.add(v + "/tolerance-profile.json");
        }
        toleranceFiles.add("tolerance-profile.schema.json");
        Map<String, Object> t = header("tolerance", versions.get(versions.size() - 1));
        t.put("toleranceProfileVersions", versions);
        t.put("files", entries(tolerance, toleranceFiles));
        write(outDir.resolve("tolerance/manifest.json"), t);

        Path vectors = repoRoot.resolve("vectors");
        List<String> vectorFiles = new ArrayList<>();
        for (String set : VECTOR_SETS) {
            try (Stream<Path> s = Files.list(vectors.resolve(set))) {
                for (String v : s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted(SEMVER)
                        .collect(Collectors.toList())) {
                    vectorFiles.add(set + "/" + v + "/manifest.json");
                }
            }
        }
        String boundaryProfile = MAPPER.readTree(vectors.resolve("boundary/" + BoundaryVectorGenerator.VERSION + "/manifest.json").toFile())
                .get("toleranceProfileVersion").asText();
        Map<String, Object> v = header("vectors", boundaryProfile);
        v.put("files", entries(vectors, vectorFiles));
        write(outDir.resolve("vectors/manifest.json"), v);
    }

    private static Map<String, Object> header(String name, String profileVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manifest", name);
        m.put("version", VERSION_PLACEHOLDER);
        m.put("toleranceProfileVersion", profileVersion);
        return m;
    }

    private static List<Object> entries(Path base, List<String> paths) throws IOException {
        List<Object> list = new ArrayList<>();
        for (String p : paths) {
            byte[] data = Files.readAllBytes(base.resolve(p));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", p);
            f.put("sha256", ConformanceVectorGenerator.sha256(data));
            f.put("bytes", data.length);
            list.add(f);
        }
        return list;
    }

    private static List<Integer> semver(String v) {
        List<Integer> parts = new ArrayList<>();
        for (String p : v.split("\\.")) {
            parts.add(Integer.parseInt(p));
        }
        return parts;
    }

    private static void write(Path file, Object json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, (MAPPER.writer(PRETTY).writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
