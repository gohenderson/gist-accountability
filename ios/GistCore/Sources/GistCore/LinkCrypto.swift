import CryptoKit
import Foundation

/*
 * Sync through a shared folder, the same files as the Android app (LinkCore.kt), so an iPhone
 * and an Android phone sync with each other. Shared test files: shared/sync.
 *
 * Keys are P-256, written as base64: public keys as X.509 SubjectPublicKeyInfo, private keys
 * as PKCS#8 (what Java's key encodings are). Two sides agree on a key with ECDH, then
 * HKDF-SHA256 (salt: the connection or devices id, info "gist-link v1"). Files are sealed with
 * AES-256-GCM: "GIST1", a 12-byte nonce, then the ciphertext and tag; the file's path (its
 * label) is the associated data, so a file can't be moved to stand in for another.
 */
public enum LinkCrypto {
    static let magic = Data("GIST1".utf8)

    /// A new key pair: (public, private), each base64.
    public static func newKeyPair() -> (publicKey: String, privateKey: String) {
        let k = P256.KeyAgreement.PrivateKey()
        return (k.publicKey.derRepresentation.base64EncodedString(), k.derRepresentation.base64EncodedString())
    }

    /// The public key for a private one (both base64), or nil if it isn't a key.
    public static func publicKey(of privateKey: String) -> String? {
        guard let d = Data(base64Encoded: privateKey), let k = try? P256.KeyAgreement.PrivateKey(derRepresentation: d)
        else { return nil }
        return k.publicKey.derRepresentation.base64EncodedString()
    }

    /// The shared key: the same on both sides (my private + their public = their private + my public).
    public static func sharedKey(myPrivate: String, theirPublic: String, linkId: String) -> Data? {
        guard let pd = Data(base64Encoded: myPrivate), let qd = Data(base64Encoded: theirPublic),
              let mine = try? P256.KeyAgreement.PrivateKey(derRepresentation: pd),
              let theirs = try? P256.KeyAgreement.PublicKey(derRepresentation: qd),
              let secret = try? mine.sharedSecretFromKeyAgreement(with: theirs)
        else { return nil }
        let key = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(linkId.utf8),
                                                 sharedInfo: Data("gist-link v1".utf8), outputByteCount: 32)
        return key.withUnsafeBytes { Data($0) }
    }

    /// "4F7A-91C2": both sides show the same code when nobody swapped a key.
    public static func code(_ keyA: String, _ keyB: String) -> String {
        let text = [keyA, keyB].sorted().joined(separator: "|")
        let hex = SHA256.hash(data: Data(text.utf8)).prefix(4).map { String(format: "%02X", $0) }.joined()
        return String(hex.prefix(4)) + "-" + String(hex.dropFirst(4))
    }

    /// Encrypts [plain] for a file; [label] (where it's written) must match to open it.
    public static func seal(key: Data, label: String, plain: Data) -> Data {
        seal(key: key, label: label, plain: plain, nonce: AES.GCM.Nonce())
    }

    static func seal(key: Data, label: String, plain: Data, nonce: AES.GCM.Nonce) -> Data {
        // Sealing only fails for a key of the wrong size, which is a bug here.
        let box = try! AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: nonce, authenticating: Data(label.utf8))
        return magic + Data(nonce) + box.ciphertext + box.tag
    }

    /// The plain text, or nil when it's not ours, was changed, or was moved from elsewhere.
    public static func open(key: Data, label: String, sealed: Data) -> Data? {
        let m = magic.count
        guard sealed.count > m + 12 + 16, sealed.prefix(m) == magic else { return nil }
        let bytes = Data(sealed)
        guard let nonce = try? AES.GCM.Nonce(data: bytes[m..<(m + 12)]),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: bytes[(m + 12)..<(bytes.count - 16)],
                                               tag: bytes[(bytes.count - 16)...])
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: Data(label.utf8))
    }

    static func sha256(_ d: Data) -> Data { Data(SHA256.hash(data: d)) }

    static func hmac(key: Data, _ message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }
}

/// One side's hello: who's there and their public key; a joiner also proves it had the
/// invite's token ([proof], as of [joinedAt]).
public struct LinkHello: Equatable {
    public var side: String
    public var name: String
    public var key: String
    public var proof: String?
    public var joinedAt: Int?

    public init(side: String, name: String, key: String, proof: String? = nil, joinedAt: Int? = nil) {
        self.side = side
        self.name = name
        self.key = key
        self.proof = proof
        self.joinedAt = joinedAt
    }
}

/// An invite, passed outside the folder (a QR code, or a code to type): its kind ("c" a
/// connection, "d" your devices), a fingerprint of the inviting side's public key, and a
/// one-time token the joiner proves it has. The token never goes in the folder.
public struct LinkInvite: Equatable {
    public var kind: Character
    public var fingerprint: Data
    public var token: Data

    public init(kind: Character, fingerprint: Data, token: Data) {
        self.kind = kind
        self.fingerprint = fingerprint
        self.token = token
    }

    /// The text code: 36 letters and digits in groups of 4 ("ABCD-EFGH-…").
    public var code: String {
        var bytes = Data([1, kind.asciiValue ?? 0])
        bytes += fingerprint
        bytes += token
        let b = LinkInvites.base32(bytes)
        return stride(from: 0, to: b.count, by: 4).map { i -> String in
            let start = b.index(b.startIndex, offsetBy: i)
            let end = b.index(start, offsetBy: min(4, b.count - i))
            return String(b[start..<end])
        }.joined(separator: "-")
    }

    /// What the QR code holds.
    public var qr: String { "GIST:" + code.replacingOccurrences(of: "-", with: "") }

    /// An invite from a scanned or typed code (spaces, dashes and case don't matter).
    public static func parse(_ text: String) -> LinkInvite? {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.uppercased().hasPrefix("GIST:") { t = String(t.dropFirst(5)) }
        let raw = t.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        guard let bytes = LinkInvites.unbase32(raw), bytes.count == 2 + LinkInvites.fingerprintSize + LinkInvites.tokenSize,
              bytes[0] == 1 else { return nil }
        let kind = Character(Unicode.Scalar(bytes[1]))
        guard kind == "c" || kind == "d" else { return nil }
        return LinkInvite(kind: kind, fingerprint: bytes.subdata(in: 2..<(2 + LinkInvites.fingerprintSize)),
                          token: bytes.subdata(in: (2 + LinkInvites.fingerprintSize)..<bytes.count))
    }
}

public enum LinkInvites {
    public static let fingerprintSize = 10
    public static let tokenSize = 10
    /// How long an invite can be used.
    public static let validMs = 15 * 60 * 1000
    static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    public static func fingerprint(_ publicKey: String) -> Data {
        LinkCrypto.sha256(Data(publicKey.utf8)).prefix(fingerprintSize)
    }

    public static func newToken() -> Data {
        var d = Data(count: tokenSize)
        for i in 0..<tokenSize { d[i] = UInt8.random(in: 0...255) }
        return d
    }

    /// Proof that side [side] with [publicKey], joining [scope] at [joinedAt], had [token].
    public static func proof(token: Data, scope: String, side: String, publicKey: String, joinedAt: Int) -> String {
        LinkCrypto.hmac(key: token, Data("gist-join|\(scope)|\(side)|\(publicKey)|\(joinedAt)".utf8)).base64EncodedString()
    }

    /// [h] joined [scope] with [token], before [expires].
    public static func proves(_ h: LinkHello, token: Data, scope: String, expires: Int) -> Bool {
        guard let p = h.proof, let at = h.joinedAt, at <= expires else { return false }
        let expected = proof(token: token, scope: scope, side: h.side, publicKey: h.key, joinedAt: at)
        // Constant time.
        let a = Array(p.utf8), b = Array(expected.utf8)
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public static func base32(_ bytes: Data) -> String {
        var out = ""
        var buffer = 0, bits = 0
        for b in bytes {
            buffer = (buffer << 8) | Int(b)
            bits += 8
            while bits >= 5 {
                out.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
            buffer &= (1 << bits) - 1
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return out
    }

    public static func unbase32(_ text: String) -> Data? {
        var out = Data()
        var buffer = 0, bits = 0
        for ch in text {
            guard let v = alphabet.firstIndex(of: ch) else { return nil }
            buffer = (buffer << 5) | v
            bits += 5
            if bits >= 8 {
                out.append(UInt8((buffer >> (bits - 8)) & 0xff))
                bits -= 8
            }
            buffer &= (1 << bits) - 1
        }
        return out
    }
}
