import XCTest
@testable import GistCore

/// The sync folder, written and read on this side: changes, snapshots, receipts, clean-up.
final class LinkProtocolTests: XCTestCase {
    private var dir: URL!
    private var f: FileFolder { FileFolder(dir) }

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("link-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    func testJSONTextKeepsOrderAndRoundTrips() throws {
        let text = #"{"b":1,"a":[true,null,2.5,"x\"yé😀"],"c":{"z":-3}}"#
        let v = try JSONText.parse(text)
        XCTAssertEqual(v.objectValue?.keys, ["b", "a", "c"])
        XCTAssertEqual(try JSONText.parse(JSONText.string(v)), v)
        XCTAssertEqual(v.objectValue?.array("a")?[3].stringValue, "x\"yé😀")
        XCTAssertThrowsError(try JSONText.parse("{\"a\":}"))
    }

    func testPathsStayInsideTheFolder() {
        XCTAssertNoThrow(try LinkPath.parts("People/Sam's/from-ab/hello.json"))
        XCTAssertNoThrow(try LinkPath.parts("My devices/gist-devices.json"))
        for bad in ["../x", "a/../b", "/abs", "a//b", ".hidden", "a\\b", "c:x", ""] {
            XCTAssertThrowsError(try LinkPath.parts(bad), bad)
        }
        XCTAssertFalse(LinkProtocol.validSide("../x"))
        XCTAssertTrue(LinkProtocol.validSide("ab12CD"))
    }

    func testPublishThenReceiveAndCleanUp() throws {
        let (pubA, privA) = LinkCrypto.newKeyPair()
        let (pubB, privB) = LinkCrypto.newKeyPair()
        try LinkProtocol.invite(f, linkId: "L1", hello: LinkHello(side: "a", name: "Alex", key: pubA))
        try LinkProtocol.writeHello(f, LinkHello(side: "b", name: "Jess", key: pubB))
        XCTAssertEqual(LinkProtocol.linkId(f), "L1")
        let key = try XCTUnwrap(LinkCrypto.sharedKey(myPrivate: privA, theirPublic: pubB, linkId: "L1"))
        XCTAssertEqual(key, LinkCrypto.sharedKey(myPrivate: privB, theirPublic: pubA, linkId: "L1"))

        let one = ["x": #"{"id":"x","n":1}"#, "y": #"{"id":"y"}"#]
        try LinkProtocol.publish(f, linkId: "L1", side: "a", key: key, seq: 1, set: one, removed: [], all: one)
        let two = ["x": #"{"id":"x","n":2}"#]
        let d = LinkProtocol.diff(one, two)
        XCTAssertEqual(d.set, ["x": #"{"id":"x","n":2}"#])
        XCTAssertEqual(d.removed, ["y"])
        try LinkProtocol.publish(f, linkId: "L1", side: "a", key: key, seq: 2, set: d.set, removed: d.removed, all: two)

        let (seq, items) = LinkProtocol.receive(f, linkId: "L1", side: "a", key: key, seq: 0, have: [:])
        XCTAssertEqual(seq, 2)
        XCTAssertEqual(items, two)
        // The wrong key reads nothing.
        XCTAssertEqual(LinkProtocol.receive(f, linkId: "L1", side: "a", key: Data(count: 32), seq: 0, have: [:]).seq, 0)

        XCTAssertEqual(LinkProtocol.cleanUp(f, side: "a", readers: ["b"]), 0, "not read yet")
        try LinkProtocol.writeReceipts(f, side: "b", read: ["a": 1])
        XCTAssertEqual(LinkProtocol.cleanUp(f, side: "a", readers: ["b"]), 1)
        XCTAssertEqual(f.names("from-a").filter { LinkProtocol.isChangeFile($0) }, ["000002.gist"])
        // Behind (change 1 gone): starts from the snapshot.
        XCTAssertEqual(LinkProtocol.receive(f, linkId: "L1", side: "a", key: key, seq: 0, have: [:]).items, two)
    }

    func testRenameKeepsTheProof() throws {
        try LinkProtocol.writeHello(f, LinkHello(side: "b", name: "Jess", key: "K", proof: "P", joinedAt: 5))
        try LinkProtocol.rename(f, side: "b", name: "Jessie", key: "K")
        XCTAssertEqual(LinkProtocol.hello(f, "b"), LinkHello(side: "b", name: "Jessie", key: "K", proof: "P", joinedAt: 5))
    }

    func testAFolderInsideAnother() throws {
        let sub = try SubFolder(f, "People/Jess")
        try sub.write("from-a/hello.json", Data("hi".utf8))
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("People/Jess/from-a/hello.json")), Data("hi".utf8))
        XCTAssertEqual(sub.names(), ["from-a"])
        sub.delete("from-a")
        XCTAssertEqual(sub.names(), [])
    }
}
