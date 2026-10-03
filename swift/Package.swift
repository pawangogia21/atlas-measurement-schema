// swift-tools-version:5.9
import PackageDescription

// Published as atlas-measurement-schema-swift (AT-16, design 19.1, 31.10): the generated types (swift/generate-types.sh)
// and the tolerance function port. Kits pin a released version tag; nothing generated is committed in a Kit.
let package = Package(
    name: "atlas-measurement-schema-swift",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "AtlasMeasurementSchema", targets: ["AtlasMeasurementSchema"]),
        .library(name: "AtlasMeasurementTolerance", targets: ["AtlasMeasurementTolerance"])
    ],
    targets: [
        .target(name: "AtlasMeasurementSchema"),
        .target(name: "AtlasMeasurementTolerance"),
        .testTarget(name: "AtlasMeasurementToleranceTests", dependencies: ["AtlasMeasurementTolerance"]),
        .testTarget(name: "AtlasMeasurementSchemaTests", dependencies: ["AtlasMeasurementSchema"])
    ]
)
