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
            dependencies: ["MotifDSP"],
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        // Built from core/dsp by core/dsp/scripts/build-apple.sh into Frameworks/; not checked in.
        .binaryTarget(name: "MotifDSP", path: "Frameworks/MotifDSP.xcframework"),
        .testTarget(name: "MotifKitTests", dependencies: ["MotifKit"]),
    ]
)
