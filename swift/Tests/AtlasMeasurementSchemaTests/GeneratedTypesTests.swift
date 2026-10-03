import Foundation
import Testing
@testable import AtlasMeasurementSchema

@Suite struct GeneratedTypesTests {
    private static let examples = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("json-schema/v1/examples")
    private static let negative = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("vectors/negative/1.0.0")

    private func data(_ name: String) throws -> Data {
        try Data(contentsOf: Self.examples.appendingPathComponent(name))
    }

    @Test func testExamplesDecodeAndRoundTrip() throws {
        for name in ["live-measurement-object-box.json", "live-measurement-point-to-point.json", "live-measurement-plane-distance.json"] {
            let m = try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: data(name))
            let again = try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: AtlasMeasurementJSON.encoder().encode(m))
            #expect(again.clientMeasurementID == m.clientMeasurementID, Comment(rawValue: name))
            #expect(again.timestamp == m.timestamp, Comment(rawValue: name))
        }
        let c = try AtlasMeasurementJSON.decoder().decode(ClientCapture.self, from: data("client-capture.json"))
        #expect(c.depthTier == .a)
    }

    @Test func testFieldsFollowTheContract() throws {
        let m = try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: data("live-measurement-object-box.json"))
        #expect(m.mode == .objectBox)
        #expect(m.trust == .unverifiedEstimate)
        #expect(m.dimensions.lengthM?.value == 0.602)
        #expect(m.frameOfReference.worldOriginEpoch == 2)
        #expect(m.qualityFlags == [.partialView])
    }

    @Test func testUnknownEnumSymbolsAreRejected() throws {
        for id in ["unknown-enum-mode", "unknown-enum-state", "unknown-enum-depth-tier", "unknown-enum-scale-source", "unknown-enum-quality-flag"] {
            let bad = try Data(contentsOf: Self.negative.appendingPathComponent(id + ".json"))
            #expect(throws: DecodingError.self, Comment(rawValue: id)) { try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: bad) }
        }
    }

    @Test func testBatchRequestHoldsLiveMeasurements() throws {
        let item = try String(contentsOf: Self.examples.appendingPathComponent("live-measurement-point-to-point.json"))
        let batch = try AtlasMeasurementJSON.decoder().decode(ClientMeasurementsBatchRequest.self, from: Data("{\"items\":[\(item)]}".utf8))
        #expect(batch.items.count == 1)
        #expect(batch.items[0].mode == .pointToPoint)
    }

    @Test func testGeometryFollowsTheContract() throws {
        let p2p = try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: data("live-measurement-point-to-point.json"))
        #expect(p2p.geometry?.endpointsWorld?.count == 2)
        #expect(p2p.geometry?.plane == nil)
        let plane = try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: data("live-measurement-plane-distance.json"))
        #expect(plane.geometry?.plane?.normalWorld == [0, 1, 0])
        #expect(plane.geometry?.plane?.offsetM == 0)
    }

    /// A Kit never has to trust an overflowing literal: either the decoder refuses it, or the value is not finite and the tolerance
    /// function throws on it (see AtlasMeasurementTolerance); it never reaches a trap or a silent AGREE.
    @Test func testAnOverflowingNumberIsNeverAFiniteValue() throws {
        for id in ["value-1e999-overflows-a-double", "value-negative-1e999", "yaw-1e999"] {
            let bad = try Data(contentsOf: Self.negative.appendingPathComponent(id + ".json"))
            if let m = try? AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: bad) {
                let values = [m.dimensions.lengthM?.value, m.obb?.yawRAD].compactMap { $0 }
                #expect(values.contains { !$0.isFinite }, Comment(rawValue: id))
            }
        }
    }

    /// The server rejects duplicate keys and trailing content (S7). Foundation's decoder is lenient on duplicate keys and
    /// picks one of the values (the first, with the Foundation of this toolchain; other parsers pick the last), which is
    /// exactly the parser differential the server closes by rejecting the document. A Kit only ever produces records and
    /// must never rely on its decoder to catch a duplicate.
    @Test func testDuplicateKeysAreNotCaughtByTheDecoderButTheTrailingDocumentIs() throws {
        let bad = try Data(contentsOf: Self.negative.appendingPathComponent("duplicate-key-hides-a-bad-value.json"))
        if let m = try? AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: bad) {
            #expect(m.confidence == 5 || m.confidence == 0.5)
        }
        let trailing = try Data(contentsOf: Self.negative.appendingPathComponent("trailing-content-second-document.json"))
        #expect(throws: (any Error).self) { try AtlasMeasurementJSON.decoder().decode(LiveMeasurement.self, from: trailing) }
    }
}
