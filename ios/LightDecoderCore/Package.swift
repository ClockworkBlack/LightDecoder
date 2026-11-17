// swift-tools-version:5.9
import PackageDescription

// The iPhone app's logic that needs no screen: the Matrix client, the chat
// state and its rules. Plain Swift (Foundation only), so it is compiled and
// tested on any machine, not only a Mac.
let package = Package(
    name: "LightDecoderCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "LightDecoderCore", targets: ["LightDecoderCore"])],
    targets: [
        .target(name: "LightDecoderCore"),
        .testTarget(name: "LightDecoderCoreTests", dependencies: ["LightDecoderCore"]),
    ]
)
