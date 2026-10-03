import Foundation
import Testing
@testable import AtlasMeasurementTolerance

@Suite struct ToleranceTests {
    private static let toleranceDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("tolerance")

    private static let boundaryDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("vectors/boundary/1.0.0")

    private func smoke() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.toleranceDir.appendingPathComponent("smoke-cases.json"))
        return try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func boundary() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.boundaryDir.appendingPathComponent("boundary-cases.json"))
        return try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func base() throws -> ToleranceProfile {
        try ToleranceProfile(contentsOf: Self.toleranceDir.appendingPathComponent("tolerance-profile.json"))
    }

    private func num(_ d: [String: Any], _ k: String) -> Double { (d[k] as! NSNumber).doubleValue }

    @Test func testProfileIsVersion100WithTheFourRows() throws {
        let p = try base()
        #expect(p.version == "1.0.0")
        #expect(try p.row("agree", algorithmMajor: 1)
                == ToleranceRow(name: "agree", floorM: 0.02, relFrac: 0.02, sigmaK: 2, sigmaCapM: 0.10))
        #expect(try p.row("accuracyGate", algorithmMajor: 1)
                == ToleranceRow(name: "accuracyGate", floorM: 0.01, relFrac: 0.02, sigmaK: nil, sigmaCapM: nil))
        #expect(throws: ToleranceError.self) { try p.row("nope", algorithmMajor: 1) }
    }

    @Test func testSmokeTolCases() throws {
        let s = try smoke()
        let eps = num(s, "tolEpsilon")
        let over = try ToleranceProfile(json: JSONSerialization.data(withJSONObject: s["overrideProfile"]!))
        let b = try base()
        for c in s["tol"] as! [[String: Any]] {
            let p = (c["profile"] as! String) == "base" ? b : over
            let row = try p.row(c["row"] as! String, algorithmMajor: (c["major"] as! NSNumber).intValue)
            let t = try Tolerance.tol(ref: num(c, "ref"), sigmaServer: num(c, "sigmaServer"),
                                      sigmaClient: num(c, "sigmaClient"), row: row)
            #expect(abs(t - num(c, "tol")) <= eps, Comment(rawValue: c["id"] as! String))
        }
    }

    @Test func testSmokeWithinCases() throws {
        for c in try smoke()["within"] as! [[String: Any]] {
            #expect(Tolerance.withinTolerance(diff: num(c, "diff"), tol: num(c, "tol"))
                    == (c["within"] as! NSNumber).boolValue, Comment(rawValue: c["id"] as! String))
        }
    }

    @Test func testSmokeClassifyCases() throws {
        let b = try base()
        for c in try smoke()["classify"] as! [[String: Any]] {
            let dims = (c["dims"] as! [[String: Any]]).map {
                DimensionPair(server: num($0, "server"), sigmaServer: num($0, "sigmaServer"),
                              client: num($0, "client"), sigmaClient: num($0, "sigmaClient"))
            }
            let o = try Tolerance.classify(profile: b, algorithmMajor: (c["major"] as! NSNumber).intValue, dims: dims)
            #expect(o.rawValue == c["outcome"] as! String, Comment(rawValue: c["id"] as! String))
        }
    }

    @Test func testInvalidInputsAreErrors() throws {
        let row = try base().row("agree", algorithmMajor: 1)
        #expect(throws: ToleranceError.self) { try Tolerance.tol(ref: 1, sigmaServer: -0.1, sigmaClient: 0, row: row) }
        #expect(throws: ToleranceError.self) { try Tolerance.tol(ref: .nan, sigmaServer: 0, sigmaClient: 0, row: row) }
        #expect(throws: ToleranceError.self) { try Tolerance.tol(ref: 1, sigmaServer: 0, sigmaClient: .infinity, row: row) }
    }

    @Test func testUnitsRoundHalfUp() {
        #expect(Tolerance.units(0.020004) == 2000)
        #expect(Tolerance.units(0.020006) == 2001)
    }

    @Test func testBoundaryTolCases() throws {
        let b = try boundary()
        let eps = num(b, "tolEpsilon")
        let base = try base()
        for c in b["tol"] as! [[String: Any]] {
            let row = try base.row(c["row"] as! String, algorithmMajor: (c["major"] as! NSNumber).intValue)
            let t = try Tolerance.tol(ref: num(c, "ref"), sigmaServer: num(c, "sigmaServer"),
                                      sigmaClient: num(c, "sigmaClient"), row: row)
            #expect(abs(t - num(c, "tol")) <= eps, Comment(rawValue: c["id"] as! String))
        }
    }

    @Test func testBoundaryWithinCases() throws {
        for c in try boundary()["within"] as! [[String: Any]] {
            #expect(Tolerance.withinTolerance(diff: num(c, "diff"), tol: num(c, "tol"))
                    == (c["within"] as! NSNumber).boolValue, Comment(rawValue: c["id"] as! String))
        }
    }

    @Test func testBoundaryClassifyCases() throws {
        let base = try base()
        for c in try boundary()["classify"] as! [[String: Any]] {
            let dims = (c["dims"] as! [[String: Any]]).map {
                DimensionPair(server: num($0, "server"), sigmaServer: num($0, "sigmaServer"),
                              client: num($0, "client"), sigmaClient: num($0, "sigmaClient"))
            }
            let o = try Tolerance.classify(profile: base, algorithmMajor: (c["major"] as! NSNumber).intValue, dims: dims)
            #expect(o.rawValue == c["outcome"] as! String, Comment(rawValue: c["id"] as! String))
        }
    }
}
