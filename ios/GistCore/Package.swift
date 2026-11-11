// swift-tools-version:5.10
// GistCore: the iPhone app's logic, ported from the Android app (android/). Models, the
// backup format (shared with Android: see shared/backups), alarm scheduling, math
// problems, scores, stats and sorting. No UI here, so it's tested with plain `swift test`.

import PackageDescription

let package = Package(
    name: "GistCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "GistCore", targets: ["GistCore"]),
    ],
    dependencies: [
        // YAML for backup files (MIT). The Android app uses SnakeYAML Engine for the same files.
        .package(url: "https://github.com/jpsim/Yams.git", from: "5.1.0"),
    ],
    targets: [
        .target(name: "GistCore", dependencies: ["Yams"]),
        .testTarget(name: "GistCoreTests", dependencies: ["GistCore"]),
    ]
)
