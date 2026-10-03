import Foundation

/// Body of `POST /api/v1/client-measurements:batch` (json-schema/v1/client-measurements-batch.schema.json): 1 to 100
/// `LiveMeasurement` items. Hand-written because the generator cannot share the `LiveMeasurement` types between two
/// entry schemas; the schema example is decoded in the tests.
public struct ClientMeasurementsBatchRequest: Codable {
    public let items: [LiveMeasurement]

    public init(items: [LiveMeasurement]) {
        self.items = items
    }
}
