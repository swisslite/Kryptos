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
            name: "CArgon2",
            path: "Sources/CArgon2",
            exclude: ["LICENSE"],
            cSettings: [
                .define("ARGON2_NO_THREADS"),
                .headerSearchPath(".")
            ]
        ),
        .target(
            name: "CZlib",
            path: "Sources/CZlib",
            linkerSettings: [
                .linkedLibrary("z")
            ]
        ),
        .target(
            name: "CipherCore",
            dependencies: ["CArgon2", "CZlib"],
            path: "Sources/CipherCore"
        )
    ]
)
