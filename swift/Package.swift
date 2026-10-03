// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AtlasMeasurementTolerance",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "AtlasMeasurementTolerance", targets: ["AtlasMeasurementTolerance"])
    ],
    targets: [
        .target(name: "AtlasMeasurementTolerance"),
        .testTarget(name: "AtlasMeasurementToleranceTests", dependencies: ["AtlasMeasurementTolerance"])
    ]
)
