// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MotifKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MotifKit", targets: ["MotifKit"]),
    ],
    targets: [
        .target(
            name: "MotifKit",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .testTarget(name: "MotifKitTests", dependencies: ["MotifKit"]),
    ]
)
