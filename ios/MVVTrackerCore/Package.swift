// swift-tools-version:5.9
import PackageDescription

// MVVTrackerCore: the platform-independent, dependency-free heart of the app.
//
// Everything here is a faithful port of the Android "source of truth" logic
// (polyline decode/interpolation, transitous DTOs, live-position computation,
// nearest-city). It has NO Firebase / UIKit / MapKit dependency, so it compiles
// and unit-tests with the plain `swift` CLI — no Xcode required. The SwiftUI app
// target added later depends on this package as a local Swift package.
let package = Package(
    name: "MVVTrackerCore",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [
        .library(name: "MVVTrackerCore", targets: ["MVVTrackerCore"])
    ],
    targets: [
        .target(name: "MVVTrackerCore"),
        // XCTest-based tests (run under Xcode once installed):
        .testTarget(name: "MVVTrackerCoreTests", dependencies: ["MVVTrackerCore"]),
        // XCTest-free verifier so the ported logic can be checked with the plain
        // Swift toolchain (no Xcode). Run with: `swift run CoreVerify`.
        .executableTarget(name: "CoreVerify", dependencies: ["MVVTrackerCore"])
    ]
)
