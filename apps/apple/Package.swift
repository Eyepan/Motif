// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MotifKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MotifKit", targets: ["MotifKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/scinfu/SwiftSoup.git", exact: "2.9.6"),
    ],
    targets: [
        .target(
            name: "MotifKit",
            dependencies: ["MotifDSP", "SwiftSoup"],
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        // Built from core/dsp by core/dsp/scripts/build-apple.sh into Frameworks/; not checked in.
        .binaryTarget(name: "MotifDSP", path: "Frameworks/MotifDSP.xcframework"),
        .testTarget(name: "MotifKitTests", dependencies: ["MotifKit", "MotifDSP"], resources: [.copy("Fixtures")]),
    ]
)
