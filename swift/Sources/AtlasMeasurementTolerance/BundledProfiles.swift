import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// Every released `tolerance-profile.json` is a resource of this package, one folder per version, and `manifest.json`
/// lists the version, the profile versions and the sha256 of each bundled file (AT-16 F9, section 35.5). A Kit selects
/// the profile of the release it pinned (vectors released against 1.0.0 keep replaying against 1.0.0 after a retune
/// ships 1.1.0) instead of copying the file.
extension ToleranceProfile {
    private static var resourceRoot: URL {
        Bundle.module.resourceURL!.appendingPathComponent("tolerance")
    }

    /// The profile versions bundled in this package, oldest first (from `manifest.json`).
    public static func bundledVersions() throws -> [String] {
        let manifest = try BundledManifest.read(resourceRoot.appendingPathComponent("manifest.json"))
        return manifest.toleranceProfileVersions
    }

    /// One released profile by version.
    public static func bundled(version: String) throws -> ToleranceProfile {
        guard version.range(of: "^[0-9]+\\.[0-9]+\\.[0-9]+$", options: .regularExpression) != nil else {
            throw ToleranceError.invalidProfile("not a profile version: \(version)")
        }
        let url = resourceRoot.appendingPathComponent(version).appendingPathComponent("tolerance-profile.json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ToleranceError.invalidProfile("no bundled tolerance profile \(version)")
        }
        let profile = try ToleranceProfile(contentsOf: url)
        guard profile.version == version else {
            throw ToleranceError.invalidProfile("profile folder \(version) holds version \(profile.version)")
        }
        return profile
    }

    /// The release manifest of the bundled profiles: the release version, the newest profile version and the file list.
    public static func bundledManifest() throws -> BundledManifest {
        try BundledManifest.read(resourceRoot.appendingPathComponent("manifest.json"))
    }

    /// Checks every bundled file against the sha256 of `manifest.json`; throws on the first mismatch.
    public static func verifyBundledChecksums() throws {
        let manifest = try bundledManifest()
        for f in manifest.files {
            try BundledManifest.verify(resourceRoot.appendingPathComponent(f.path), sha256: f.sha256)
        }
    }
}

/// The `manifest.json` of a bundled resource folder: release `version`, `toleranceProfileVersion`, per-file sha256.
public struct BundledManifest {
    public struct File {
        public let path: String
        public let sha256: String
    }

    public let version: String
    public let toleranceProfileVersion: String
    public let toleranceProfileVersions: [String]
    public let files: [File]

    public static func read(_ url: URL) throws -> BundledManifest {
        guard let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let version = root["version"] as? String,
              let profile = root["toleranceProfileVersion"] as? String,
              let files = root["files"] as? [[String: Any]]
        else { throw ToleranceError.invalidProfile("bad manifest \(url.lastPathComponent)") }
        let parsed = try files.map { f -> File in
            guard let path = f["path"] as? String, let sha = f["sha256"] as? String else {
                throw ToleranceError.invalidProfile("bad manifest entry \(f)")
            }
            return File(path: path, sha256: sha)
        }
        return BundledManifest(version: version, toleranceProfileVersion: profile,
                               toleranceProfileVersions: root["toleranceProfileVersions"] as? [String] ?? [],
                               files: parsed)
    }

    /// Throws unless the file's sha256 equals `sha256` (CryptoKit; unavailable off Apple platforms).
    public static func verify(_ url: URL, sha256 expected: String) throws {
        #if canImport(CryptoKit)
        let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
        guard digest == expected else {
            throw ToleranceError.invalidProfile("checksum mismatch for \(url.lastPathComponent)")
        }
        #else
        throw ToleranceError.invalidProfile("checksum verification needs CryptoKit")
        #endif
    }
}
