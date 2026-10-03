import Foundation

// Port of reference/.../Tolerance.java. Normative text: tolerance/README.md.
// Thresholds come only from tolerance-profile.json (never constants in code).

/// One effective profile row. `sigmaK == nil`: no sigma term; `sigmaCapM == nil`: sigma term uncapped.
public struct ToleranceRow: Equatable {
    public let name: String
    public let floorM: Double
    public let relFrac: Double
    public let sigmaK: Double?
    public let sigmaCapM: Double?
}

public enum ToleranceError: Error, Equatable {
    case invalidProfile(String)
    case unknownRow(String)
    case invalidInput(String)
}

public enum Outcome: String {
    case agree = "AGREE"
    case minorDiff = "MINOR_DIFF"
    case majorDiff = "MAJOR_DIFF"
}

/// One dimension of an associated pair.
public struct DimensionPair {
    public let server: Double
    public let sigmaServer: Double
    public let client: Double
    public let sigmaClient: Double

    public init(server: Double, sigmaServer: Double, client: Double, sigmaClient: Double) {
        self.server = server
        self.sigmaServer = sigmaServer
        self.client = client
        self.sigmaClient = sigmaClient
    }
}

/// A parsed `tolerance-profile.json`.
public struct ToleranceProfile {
    public let version: String
    private let rows: [String: ToleranceRow]
    private let overrides: [String: [String: [String: Any]]]

    public init(json: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              let version = root["version"] as? String,
              let rowList = root["rows"] as? [[String: Any]],
              let overrides = root["overrides"] as? [String: [String: [String: Any]]]
        else { throw ToleranceError.invalidProfile("missing version, rows or overrides") }
        var rows: [String: ToleranceRow] = [:]
        for r in rowList {
            guard let name = r["name"] as? String,
                  let floorM = (r["floorM"] as? NSNumber)?.doubleValue,
                  let relFrac = (r["relFrac"] as? NSNumber)?.doubleValue,
                  r.keys.contains("sigmaK"), r.keys.contains("sigmaCapM")
            else { throw ToleranceError.invalidProfile("bad row \(r)") }
            rows[name] = ToleranceRow(
                name: name, floorM: floorM, relFrac: relFrac,
                sigmaK: ToleranceProfile.nullable(r["sigmaK"]), sigmaCapM: ToleranceProfile.nullable(r["sigmaCapM"]))
        }
        self.version = version
        self.rows = rows
        self.overrides = overrides
    }

    public init(contentsOf url: URL) throws {
        try self.init(json: Data(contentsOf: url))
    }

    /// Effective row: field-wise override of the base row for `algorithmMajor` (absent fields inherit).
    public func row(_ name: String, algorithmMajor: Int) throws -> ToleranceRow {
        guard let base = rows[name] else { throw ToleranceError.unknownRow(name) }
        guard let o = overrides[String(algorithmMajor)]?[name] else { return base }
        return ToleranceRow(
            name: name,
            floorM: (o["floorM"] as? NSNumber)?.doubleValue ?? base.floorM,
            relFrac: (o["relFrac"] as? NSNumber)?.doubleValue ?? base.relFrac,
            sigmaK: o.keys.contains("sigmaK") ? ToleranceProfile.nullable(o["sigmaK"]) : base.sigmaK,
            sigmaCapM: o.keys.contains("sigmaCapM") ? ToleranceProfile.nullable(o["sigmaCapM"]) : base.sigmaCapM)
    }

    private static func nullable(_ v: Any?) -> Double? {
        (v as? NSNumber)?.doubleValue
    }
}

public enum Tolerance {
    /// `max(floorM, relFrac*|ref|, min(sigmaK*combinedSigma, sigmaCapM))`; sigma term omitted if `sigmaK` is nil.
    public static func tol(ref: Double, sigmaServer: Double, sigmaClient: Double, row: ToleranceRow) throws -> Double {
        try requireFinite(ref, "ref")
        try requireSigma(sigmaServer, "sigmaServer")
        try requireSigma(sigmaClient, "sigmaClient")
        var t = max(row.floorM, row.relFrac * abs(ref))
        if let k = row.sigmaK {
            var sigmaTerm = k * (sigmaServer * sigmaServer + sigmaClient * sigmaClient).squareRoot()
            if let cap = row.sigmaCapM {
                sigmaTerm = min(sigmaTerm, cap)
            }
            t = max(t, sigmaTerm)
        }
        return t
    }

    /// Integer units of 1e-5 m: `floor(x * 1e5 + 0.5)` in double arithmetic, identical in every port.
    public static func units(_ metres: Double) -> Int64 {
        Int64((metres * 1e5 + 0.5).rounded(.down))
    }

    /// `diff <= tol` compared in metres rounded to 1e-5.
    public static func withinTolerance(diff: Double, tol: Double) -> Bool {
        units(diff) <= units(tol)
    }

    public static func classify(profile: ToleranceProfile, algorithmMajor: Int, dims: [DimensionPair]) throws -> Outcome {
        if dims.isEmpty { throw ToleranceError.invalidInput("no dimensions to classify") }
        if try allWithin(try profile.row("agree", algorithmMajor: algorithmMajor), dims) { return .agree }
        if try allWithin(try profile.row("minor", algorithmMajor: algorithmMajor), dims) { return .minorDiff }
        return .majorDiff
    }

    private static func allWithin(_ row: ToleranceRow, _ dims: [DimensionPair]) throws -> Bool {
        for d in dims {
            let t = try tol(ref: d.server, sigmaServer: d.sigmaServer, sigmaClient: d.sigmaClient, row: row)
            if !withinTolerance(diff: abs(d.client - d.server), tol: t) { return false }
        }
        return true
    }

    private static func requireFinite(_ v: Double, _ name: String) throws {
        if !v.isFinite { throw ToleranceError.invalidInput("\(name) must be finite") }
    }

    private static func requireSigma(_ v: Double, _ name: String) throws {
        try requireFinite(v, name)
        if v < 0 { throw ToleranceError.invalidInput("\(name) must be >= 0") }
    }
}
