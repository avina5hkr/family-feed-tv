#!/usr/bin/env bash
# Usage: scripts/check-cert.sh <apk> <expected-sha256>
#
# Checks that <apk> has a valid signature (apksigner verify) and exactly one signer whose certificate SHA-256
# equals <expected-sha256>. Colons, spaces and letter case in the fingerprint are ignored. Prints the actual
# fingerprint. Exit status 0 on a match, 1 otherwise.
#
# apksigner is taken from $APKSIGNER, else from PATH, else from the newest build-tools in $ANDROID_HOME or
# $ANDROID_SDK_ROOT.
set -euo pipefail

fail() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::error::$1" >&2; else echo "ERROR: $1" >&2; fi
  exit 1
}

[ $# -eq 2 ] || fail "usage: $0 <apk> <expected-sha256>"
apk=$1
expected=$2
[ -f "$apk" ] || fail "APK not found: $apk"

apksigner=${APKSIGNER:-}
if [ -z "$apksigner" ]; then
  if command -v apksigner >/dev/null 2>&1; then
    apksigner=$(command -v apksigner)
  else
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [ -n "$sdk" ] || fail "apksigner not found: set APKSIGNER or ANDROID_HOME"
    latest=$(ls "$sdk/build-tools" 2>/dev/null | sort -V | tail -n 1)
    [ -n "$latest" ] || fail "no build-tools found in $sdk"
    apksigner="$sdk/build-tools/$latest/apksigner"
  fi
fi
[ -x "$apksigner" ] || fail "apksigner is not executable: $apksigner"

normalise() { tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }

want=$(printf '%s' "$expected" | normalise)
[[ "$want" =~ ^[0-9a-f]{64}$ ]] || fail "expected fingerprint is not a SHA-256 (64 hex digits after removing colons)"

if ! out=$("$apksigner" verify --print-certs "$apk" 2>&1); then
  printf '%s\n' "$out" >&2
  fail "APK signature does not verify: $apk"
fi

signers=$(printf '%s\n' "$out" | grep -c 'certificate SHA-256 digest:' || true)
[ "$signers" -eq 1 ] || fail "expected exactly 1 signer, found $signers"

got=$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | normalise)
[ -n "$got" ] || fail "could not read the signer certificate SHA-256 from apksigner output"

echo "Signer certificate SHA-256: $got"
[ "$got" = "$want" ] || fail "certificate fingerprint does not match ANDROID_SIGNING_CERT_SHA256 (expected $want)"
echo "Certificate fingerprint matches."
