# Sync test files

Both apps' tests read these, so an iPhone and an Android phone are known to write and read
the same sync files:

- `files/vectors.json`: fixed keys, the shared key they agree on, the pair code, a sealed file,
  an invite code and a join proof.
- `files/folder/`: a connection folder as either app writes it (side A shared two items, then
  changed one and removed the other; side B joined with an invite and read change 1).

`files/` is what the tests read (the Android tests get it as assets). Checked by `android/app/src/androidTest/.../SyncVectorsTest.kt` and
`ios/GistCore/Tests/GistCoreTests/SyncVectorsTests.swift`.

`make_vectors.py` makes them (fixed keys and nonces, so its output doesn't change):
`python3 shared/sync/make_vectors.py` (needs the `cryptography` package).
