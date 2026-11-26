import CryptoKit
import XCTest
@testable import GistCore

/// The shared sync test files (shared/sync, made by make_vectors.py), the same ones the
/// Android app's SyncVectorsTest checks: keys, encryption, invite codes and a folder written
/// as Android writes it, so an iPhone and an Android phone sync with each other.
final class SyncVectorsTests: XCTestCase {
    static let shared: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("shared/sync/files")
    }()

    private var v: JSONObject!

    override func setUpWithError() throws {
        v = try XCTUnwrap(JSONText.object(try Data(contentsOf: Self.shared.appendingPathComponent("vectors.json"))))
    }

    private func hex(_ s: String) -> Data {
        var d = Data()
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return d
    }

    private func s(_ path: String) -> String {
        let parts = path.split(separator: ".").map(String.init)
        var o: JSONObject = v
        for p in parts.dropLast() { o = o.object(p)! }
        return o.string(parts.last!)
    }

    func testKeysAgreeAndShowTheSameCode() throws {
        let k1 = try XCTUnwrap(LinkCrypto.sharedKey(myPrivate: s("a.private"), theirPublic: s("b.public"), linkId: s("link")))
        let k2 = try XCTUnwrap(LinkCrypto.sharedKey(myPrivate: s("b.private"), theirPublic: s("a.public"), linkId: s("link")))
        XCTAssertEqual(k1, hex(s("sharedKeyHex")))
        XCTAssertEqual(k1, k2)
        XCTAssertEqual(LinkCrypto.code(s("a.public"), s("b.public")), s("code"))
        XCTAssertEqual(LinkCrypto.publicKey(of: s("a.private")), s("a.public"))
    }

    func testSealsTheSameBytes() throws {
        let key = hex(s("sharedKeyHex"))
        let nonce = try AES.GCM.Nonce(data: hex(s("seal.nonceHex")))
        let sealed = LinkCrypto.seal(key: key, label: s("seal.label"), plain: Data(s("seal.plain").utf8), nonce: nonce)
        XCTAssertEqual(sealed.base64EncodedString(), s("seal.sealedBase64"))
        let opened = LinkCrypto.open(key: key, label: s("seal.label"), sealed: try XCTUnwrap(Data(base64Encoded: s("seal.sealedBase64"))))
        XCTAssertEqual(opened.map { String(decoding: $0, as: UTF8.self) }, s("seal.plain"))
        XCTAssertNil(LinkCrypto.open(key: key, label: "elsewhere", sealed: sealed), "moved to another path")
    }

    func testInviteCodesAndProofs() throws {
        let inv = LinkInvite(kind: "c", fingerprint: hex(s("invite.fingerprintHex")), token: hex(s("invite.tokenHex")))
        XCTAssertEqual(inv.code, s("invite.code"))
        XCTAssertEqual(inv.qr, s("invite.qr"))
        XCTAssertEqual(LinkInvite.parse(s("invite.code").lowercased()), inv)
        XCTAssertEqual(LinkInvite.parse(s("invite.qr")), inv)
        XCTAssertEqual(LinkInvites.fingerprint(s("a.public")), hex(s("invite.fingerprintHex")))
        let joinedAt = try XCTUnwrap(v.object("proof")?.int("joinedAt"))
        XCTAssertEqual(LinkInvites.proof(token: inv.token, scope: s("proof.scope"), side: s("proof.side"),
                                         publicKey: s("proof.publicKey"), joinedAt: joinedAt), s("proof.proof"))
    }

    func testReadsAFolderAndroidWrote() throws {
        let f = FileFolder(Self.shared.appendingPathComponent("folder"))
        XCTAssertEqual(LinkProtocol.linkId(f), s("link"))
        let b = try XCTUnwrap(LinkProtocol.hello(f, "sideb"))
        XCTAssertEqual(b.name, "Jess")
        XCTAssertEqual(b.proof, s("proof.proof"))
        let token = hex(s("invite.tokenHex"))
        XCTAssertTrue(LinkInvites.proves(b, token: token, scope: s("link"), expires: Int.max))
        XCTAssertFalse(LinkInvites.proves(b, token: token, scope: s("link"), expires: 0), "too late")
        XCTAssertEqual(LinkProtocol.sides(f, mySide: "sideb"), ["sidea"])
        XCTAssertEqual(LinkProtocol.readUpTo(f, reader: "sideb", side: "sidea"), 1)

        let key = hex(s("sharedKeyHex"))
        let (seq, items) = LinkProtocol.receive(f, linkId: s("link"), side: "sidea", key: key, seq: 0, have: [:])
        XCTAssertEqual(seq, 2)
        let expected = try XCTUnwrap(v.object("folder")?.object("items"))
        XCTAssertEqual(Set(items.keys), Set(expected.keys))
        for (id, json) in items { XCTAssertEqual(try JSONText.parse(json), expected[id]) }
        // From change 1 on, too.
        let after1 = try XCTUnwrap(v.object("folder")?.object("afterChange1"))
        var have: [String: String] = [:]
        for (id, j) in after1.pairs { have[id] = JSONText.string(j) }
        let (seq2, items2) = LinkProtocol.receive(f, linkId: s("link"), side: "sidea", key: key, seq: 1, have: have)
        XCTAssertEqual(seq2, 2)
        XCTAssertEqual(Set(items2.keys), ["walk"])
    }
}
