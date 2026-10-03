// swift-tools-version:5.9
import PackageDescription

// Published as atlas-measurement-schema-swift (AT-16, design 19.1, 31.10, section 35.5): the generated types, the
// tolerance function port with every released tolerance profile as a resource, and the vectors. Kits pin a released
// version tag and get everything from it; nothing generated or copied is committed in a Kit. Resources are staged from
// the repository sources by stage-resources.sh (git-ignored here, present in the published package).
let package = Package(
    name: "atlas-measurement-schema-swift",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "AtlasMeasurementSchema", targets: ["AtlasMeasurementSchema"]),
        .library(name: "AtlasMeasurementTolerance", targets: ["AtlasMeasurementTolerance"]),
        // the conformance, boundary and negative vectors: depend on it from test targets only, never from an app target
        .library(name: "AtlasMeasurementVectors", targets: ["AtlasMeasurementVectors"])
    ],
    targets: [
        .target(name: "AtlasMeasurementSchema"),
        .target(name: "AtlasMeasurementTolerance", resources: [.copy("Resources/tolerance")]),
        .target(name: "AtlasMeasurementVectors", resources: [.copy("Resources/vectors")]),
        .testTarget(name: "AtlasMeasurementToleranceTests", dependencies: ["AtlasMeasurementTolerance", "AtlasMeasurementVectors"]),
        .testTarget(name: "AtlasMeasurementSchemaTests", dependencies: ["AtlasMeasurementSchema"])
    ]
)
