#!/usr/bin/env python3
"""Makes the shared sync test files (files/vectors.json and files/folder/) that both apps' tests read, so
an iPhone and an Android phone are known to read and write the same sync files.

Run from anywhere: python3 shared/sync/make_vectors.py (needs the `cryptography` package).
The output is fixed (fixed keys and nonces), so re-running changes nothing unless this does.
"""
import base64, hashlib, hmac, json, os, shutil
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "files")
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def key(seed):
    d = int.from_bytes(hashlib.sha256(seed.encode()).digest(), "big") % (N - 1) + 1
    k = ec.derive_private_key(d, ec.SECP256R1())
    pub = k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    priv = k.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return k, base64.b64encode(pub).decode(), base64.b64encode(priv).decode()


def shared_key(k, other_pub_b64, link_id):
    pub = serialization.load_der_public_key(base64.b64decode(other_pub_b64))
    secret = k.exchange(ec.ECDH(), pub)
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=link_id.encode(), info=b"gist-link v1").derive(secret)


def code(a, b):
    h = hashlib.sha256("|".join(sorted([a, b])).encode()).digest()[:4].hex().upper()
    return h[:4] + "-" + h[4:]


def seal(k, label, plain, nonce):
    return b"GIST1" + nonce + AESGCM(k).encrypt(nonce, plain, label.encode())


ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def base32(b):
    out, buf, bits = "", 0, 0
    for x in b:
        buf = (buf << 8) | x
        bits += 8
        while bits >= 5:
            out += ALPHABET[(buf >> (bits - 5)) & 31]
            bits -= 5
        buf &= (1 << bits) - 1
    if bits:
        out += ALPHABET[(buf << (5 - bits)) & 31]
    return out


def compact(o):
    return json.dumps(o, separators=(",", ":"), ensure_ascii=False)


ka, pub_a, priv_a = key("gist-test-a")
kb, pub_b, priv_b = key("gist-test-b")
link = "testlink1"
k = shared_key(ka, pub_b, link)
assert k == shared_key(kb, pub_a, link)

fingerprint = hashlib.sha256(pub_a.encode()).digest()[:10]
token = bytes(range(1, 11))
invite_bytes = bytes([1, ord("c")]) + fingerprint + token
b32 = base32(invite_bytes)
invite_code = "-".join(b32[i:i + 4] for i in range(0, len(b32), 4))
joined_at = 1764000000000
proof = base64.b64encode(hmac.new(token, f"gist-join|{link}|sideb|{pub_b}|{joined_at}".encode(), hashlib.sha256).digest()).decode()

nonce = bytes(range(12))
label = f"{link}/from-sidea/000001.gist"
plain = b'{"hello":"world"}'

# A folder: side A shares two items in change 1, changes one and removes the other in
# change 2 (and its snapshot holds the result); side B has joined.
folder = os.path.join(HERE, "folder")
shutil.rmtree(folder, ignore_errors=True)
os.makedirs(os.path.join(folder, "from-sidea"))
os.makedirs(os.path.join(folder, "from-sideb"))
with open(os.path.join(folder, "gist-link.json"), "w") as f:
    f.write(compact({"format": "gist-link", "version": 1, "link": link}))
with open(os.path.join(folder, "from-sidea", "hello.json"), "w") as f:
    f.write(compact({"side": "sidea", "name": "Alex", "key": pub_a}))
with open(os.path.join(folder, "from-sideb", "hello.json"), "w") as f:
    f.write(compact({"side": "sideb", "name": "Jess", "key": pub_b, "proof": proof, "joinedAt": joined_at}))
walk = {"id": "walk", "name": "Evening walk ✓", "enabled": True, "times": ["19:30"], "lat": 40.7128}
read = {"id": "read", "name": "Read \"10\" pages", "enabled": False}
walk2 = dict(walk, name="Evening walk, longer")
change1 = {"seq": 1, "set": {"read": read, "walk": walk}, "removed": []}
change2 = {"seq": 2, "set": {"walk": walk2}, "removed": ["read"]}
snapshot = {"seq": 2, "items": {"walk": walk2}}
for name, obj, n in [("000001.gist", change1, 1), ("000002.gist", change2, 2), ("snapshot.gist", snapshot, 3)]:
    path = f"from-sidea/{name}"
    with open(os.path.join(folder, path), "wb") as f:
        f.write(seal(k, f"{link}/{path}", compact(obj).encode(), bytes([n]) * 12))
with open(os.path.join(folder, "from-sideb", "read.json"), "w") as f:
    f.write(compact({"sidea": 1}))

vectors = {
    "_about": "Made by make_vectors.py: fixed keys and values both apps' tests check against.",
    "a": {"public": pub_a, "private": priv_a},
    "b": {"public": pub_b, "private": priv_b},
    "link": link,
    "sharedKeyHex": k.hex(),
    "code": code(pub_a, pub_b),
    "seal": {"nonceHex": nonce.hex(), "label": label, "plain": plain.decode(),
             "sealedBase64": base64.b64encode(seal(k, label, plain, nonce)).decode()},
    "invite": {"kind": "c", "fingerprintHex": fingerprint.hex(), "tokenHex": token.hex(), "code": invite_code,
               "qr": "GIST:" + b32},
    "proof": {"scope": link, "side": "sideb", "publicKey": pub_b, "joinedAt": joined_at, "proof": proof},
    "folder": {"side": "sidea", "seq": 2, "items": {"walk": walk2},
               "afterChange1": {"read": read, "walk": walk}},
}
with open(os.path.join(HERE, "vectors.json"), "w") as f:
    json.dump(vectors, f, indent=2, ensure_ascii=False)
    f.write("\n")
print("ok", code(pub_a, pub_b), invite_code)
