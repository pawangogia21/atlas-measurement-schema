import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// The conformance, boundary and negative vectors of this release (AT-16 F9, section 35.5), bundled as resources so a
/// Kit pins them by the same version as the types and the tolerance profile. Depend on this library from test targets
/// only: the vectors are not meant to ship inside an app. `vectors/manifest.json` lists the release version, the
/// `toleranceProfileVersion` the vectors were released against and the sha256 of each vector set's own manifest, which
/// in turn lists the sha256 of each file of the set.
public enum AtlasMeasurementVectors {
    public enum Failure: Error, Equatable {
        case missing(String)
        case checksumMismatch(String)
        case checksumsUnavailable
    }

    public static var root: URL {
        Bundle.module.resourceURL!.appendingPathComponent("vectors")
    }

    /// A file of the vectors, by path relative to the vectors folder, e.g. `boundary/1.0.0/boundary-cases.json`.
    public static func url(_ relativePath: String) throws -> URL {
        let url = root.appendingPathComponent(relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else { throw Failure.missing(relativePath) }
        return url
    }

    /// The top-level manifest as a dictionary (`version`, `toleranceProfileVersion`, `files`).
    public static func manifest() throws -> [String: Any] {
        let data = try Data(contentsOf: url("manifest.json"))
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.missing("manifest.json") }
        return json
    }

    /// Checks the vector-set manifests against `manifest.json` and every vector file against its set manifest.
    public static func verifyChecksums() throws {
        for entry in try manifest()["files"] as? [[String: Any]] ?? [] {
            guard let path = entry["path"] as? String, let sha = entry["sha256"] as? String else { throw Failure.missing("manifest entry") }
            try verify(path, sha256: sha)
            let dir = (path as NSString).deletingLastPathComponent
            let setManifest = try JSONSerialization.jsonObject(with: Data(contentsOf: url(path))) as? [String: Any]
            for file in setManifest?["files"] as? [[String: Any]] ?? [] {
                guard let p = file["path"] as? String, let s = file["sha256"] as? String else { throw Failure.missing("set manifest entry") }
                try verify(dir + "/" + p, sha256: s)
            }
        }
    }

    private static func verify(_ relativePath: String, sha256 expected: String) throws {
        #if canImport(CryptoKit)
        let digest = SHA256.hash(data: try Data(contentsOf: url(relativePath))).map { String(format: "%02x", $0) }.joined()
        guard digest == expected else { throw Failure.checksumMismatch(relativePath) }
        #else
        throw Failure.checksumsUnavailable
        #endif
    }
}
