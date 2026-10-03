import Foundation
import Testing
@testable import AtlasMeasurementTolerance
import AtlasMeasurementVectors

/// The package carries what a Kit pins by version: every released profile, the vectors and manifests with checksums.
@Suite struct BundledResourcesTests {
    private static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    @Test func testTheBundledProfileIsTheRepositoryFileAndSelectableByVersion() throws {
        #expect(try ToleranceProfile.bundledVersions() == ["1.0.0"])
        #expect(try ToleranceProfile.bundled(version: "1.0.0").version == "1.0.0")
        let repoFile = try Data(contentsOf: Self.repo.appendingPathComponent("tolerance/1.0.0/tolerance-profile.json"))
        #expect(try ToleranceProfile.bundled(version: "1.0.0").row("agree", algorithmMajor: 1)
                == ToleranceProfile(json: repoFile).row("agree", algorithmMajor: 1))
        #expect(throws: ToleranceError.self) { try ToleranceProfile.bundled(version: "9.9.9") }
        #expect(throws: ToleranceError.self) { try ToleranceProfile.bundled(version: "../1.0.0") }
    }

    #if canImport(CryptoKit)
    @Test func testTheBundledProfilesMatchTheManifestChecksums() throws {
        try ToleranceProfile.verifyBundledChecksums()
        let manifest = try ToleranceProfile.bundledManifest()
        #expect(manifest.toleranceProfileVersion == "1.0.0")
        #expect(!manifest.version.isEmpty)
        #expect(manifest.files.map(\.path).contains("1.0.0/tolerance-profile.json"))
    }

    @Test func testTheVectorsMatchTheirManifestChecksums() throws {
        try AtlasMeasurementVectors.verifyChecksums()
    }
    #endif

    @Test func testTheVectorsAreTheRepositoryFilesAndNameTheirProfile() throws {
        let manifest = try AtlasMeasurementVectors.manifest()
        let profileVersion = try #require(manifest["toleranceProfileVersion"] as? String)
        #expect(try ToleranceProfile.bundledVersions().contains(profileVersion))
        for path in ["boundary/1.0.0/boundary-cases.json", "boundary/1.0.0/association-cases.json", "negative/1.0.0/manifest.json",
                     "conformance/1.0.0/manifest.json"] {
            #expect(try Data(contentsOf: AtlasMeasurementVectors.url(path)) == Data(contentsOf: Self.repo.appendingPathComponent("vectors/" + path)), Comment(rawValue: path))
        }
        #expect(throws: AtlasMeasurementVectors.Failure.self) { try AtlasMeasurementVectors.url("nope.json") }
    }
}
