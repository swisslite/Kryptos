// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CipherCore",
    platforms: [
        .macOS(.v13),
        .iOS(.v17)
    ],
    products: [
        .library(name: "CipherCore", targets: ["CipherCore"])
    ],
    targets: [
        .target(
            name: "CipherCore",
            path: "Sources/CipherCore"
        ),
        .testTarget(
            name: "CipherCoreTests",
            dependencies: ["CipherCore"],
            path: "Tests/CipherCoreTests"
        )
    ]
)
