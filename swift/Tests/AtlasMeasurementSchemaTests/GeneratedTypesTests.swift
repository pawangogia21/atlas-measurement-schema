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
        for name in ["live-measurement-object-box.json", "live-measurement-point-to-point.json"] {
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
}
