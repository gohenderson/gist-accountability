#!/usr/bin/env bash
# Builds Syncthing's program for Android, for Gist's Easy Connect: one executable per ABI,
# named lib*.so so Android installs it in the app's native library folder, the only place
# an app may run a program from.
#
#   build-syncthing.sh <syncthing source> <out dir> <abi>...
#
# Needs Go (the version in Syncthing's go.mod) and the Android NDK (ANDROID_NDK_LATEST_HOME
# or ANDROID_NDK_HOME, as on GitHub's runners). Writes <out>/<abi>/libsyncthing.so.
#
# -checklinkname=0: on Android, Syncthing lists network interfaces with
# github.com/wlynxg/anet, which links to Go's net internals; Go 1.23+ refuses that unless told.
set -euo pipefail

src="$(cd "$1" && pwd)"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
shift 2
ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
api=29 # Gist's minSdk
version="$(git -C "$src" describe --tags --exact-match 2>/dev/null || echo unknown-dev)"

for abi in "$@"; do
	goarm=""
	case "$abi" in
		arm64-v8a) goarch=arm64; cc="aarch64-linux-android$api-clang" ;;
		x86_64) goarch=amd64; cc="x86_64-linux-android$api-clang" ;;
		armeabi-v7a) goarch=arm; goarm=7; cc="armv7a-linux-androideabi$api-clang" ;;
		*) echo "Unknown ABI $abi" >&2; exit 1 ;;
	esac
	echo "Building Syncthing $version for $abi"
	mkdir -p "$out/$abi"
	(
		cd "$src"
		CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM="$goarm" CC="$bin/$cc" \
			go build -trimpath -tags noupgrade,noassets \
			-ldflags "-s -w -checklinkname=0 \
				-X github.com/syncthing/syncthing/lib/build.Version=$version \
				-X github.com/syncthing/syncthing/lib/build.Stamp=$(git log -1 --format=%ct) \
				-X github.com/syncthing/syncthing/lib/build.User=gist \
				-X github.com/syncthing/syncthing/lib/build.Host=github-actions \
				-X github.com/syncthing/syncthing/lib/build.Tags=noupgrade,noassets" \
			-o "$out/$abi/libsyncthing.so" ./cmd/syncthing
	)
	ls -l "$out/$abi/libsyncthing.so"
done
