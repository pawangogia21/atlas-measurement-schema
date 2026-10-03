import Foundation

/// The JSON coders for the generated types: `timestamp` is an RFC 3339 date-time and may carry fractional seconds.
/// Swift's `Codable` ignores unknown properties; the server rejects them (schema `additionalProperties: false`), so a
/// Kit only ever has to produce records, which the encoder does exactly from the generated types.
public enum AtlasMeasurementJSON {
    private static func format(withFractionalSeconds: Bool) -> ISO8601DateFormatter {
        let f = ISO8601DateFormatter()
        f.formatOptions = withFractionalSeconds ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
        return f
    }

    public static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = format(withFractionalSeconds: true).date(from: text) ?? format(withFractionalSeconds: false).date(from: text) {
                return date
            }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not an RFC 3339 date-time: \(text)"))
        }
        return d
    }

    public static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            try c.encode(format(withFractionalSeconds: true).string(from: date))
        }
        return e
    }
}
