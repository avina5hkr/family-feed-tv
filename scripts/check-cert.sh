#!/usr/bin/env bash
# Usage: scripts/check-cert.sh <apk> <expected-sha256>
#        scripts/check-cert.sh --from-output <apksigner-output-file> <expected-sha256>
#
# Checks that <apk> verifies with apksigner and that its signer certificate SHA-256 equals <expected-sha256>
# (colons, spaces and letter case are ignored). Exit status 0 on a match, 1 otherwise.
#
# Before parsing it prints diagnostics, all public: the apksigner path and version, java -version, the APK name and
# size, and apksigner's exit code and complete output. apksigner is the one in the newest build-tools directory under
# $ANDROID_HOME (or $ANDROID_SDK_ROOT).
#
# --from-output runs only the parser, on saved `apksigner verify --verbose --print-certs` output
# (used by scripts/test-check-cert.sh).
set -euo pipefail

fail() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::error::$1" >&2; else echo "ERROR: $1" >&2; fi
  exit 1
}

normalise() { tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }

# Parses apksigner output ($1, a file) and compares the signer certificate SHA-256 with $2.
# Both formats apksigner uses are accepted:
#   Signer #1 certificate SHA-256 digest: ...
#   Signer (minSdkVersion=24, maxSdkVersion=2147483647) certificate SHA-256 digest: ...
# "certificate" is part of the pattern on purpose: the same output also has "public key SHA-256 digest" lines,
# which are a different value.
check_output() {
  local file=$1 want lines digests count
  want=$(printf '%s' "$2" | normalise)
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || fail "expected fingerprint is not a SHA-256 (64 hex digits after removing colons)"

  lines=$(grep -iE 'certificate SHA-256 digest: *[0-9a-f:]+' "$file" || true)
  [ -n "$lines" ] || fail "no 'certificate SHA-256 digest' line in the apksigner output"
  echo "Matched line(s):"
  printf '%s\n' "$lines" | sed 's/^/  /'

  digests=$(printf '%s\n' "$lines" | grep -oiE 'certificate SHA-256 digest: *[0-9a-f: ]+' |
    sed -E 's/^.*[Dd][Ii][Gg][Ee][Ss][Tt]: *//' |
    while IFS= read -r d; do printf '%s' "$d" | normalise; echo; done | sort -u)
  count=$(printf '%s\n' "$digests" | grep -c . || true)
  [ "$count" -eq 1 ] || fail "the signer certificate digests differ: $(printf '%s ' $digests)"
  [[ "$digests" =~ ^[0-9a-f]{64}$ ]] || fail "the matched digest is not a SHA-256: $digests"

  echo "Signer certificate SHA-256: $digests"
  [ "$digests" = "$want" ] || fail "certificate fingerprint does not match ANDROID_SIGNING_CERT_SHA256 (expected $want)"
  echo "Certificate fingerprint matches."
}

if [ "${1:-}" = "--from-output" ]; then
  [ $# -eq 3 ] || fail "usage: $0 --from-output <apksigner-output-file> <expected-sha256>"
  [ -f "$2" ] || fail "apksigner output file not found: $2"
  check_output "$2" "$3"
  exit 0
fi

[ $# -eq 2 ] || fail "usage: $0 <apk> <expected-sha256>"
apk=$1
expected=$2
[ -f "$apk" ] || fail "APK not found: $apk"

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || fail "ANDROID_HOME (or ANDROID_SDK_ROOT) is not set"
[ -d "$sdk/build-tools" ] || fail "no build-tools directory in $sdk"
latest=$(ls -1 "$sdk/build-tools" | sort -V | tail -n 1)
[ -n "$latest" ] || fail "no build-tools versions installed in $sdk/build-tools"
apksigner="$sdk/build-tools/$latest/apksigner"
[ -x "$apksigner" ] || fail "apksigner not found or not executable: $apksigner"

echo "apksigner: $apksigner"
echo "apksigner version: $("$apksigner" version 2>&1 || echo '(could not run apksigner version)')"
echo "java -version:"
{ java -version 2>&1 || echo "(java not found)"; } | sed 's/^/  /'
echo "APK: $(basename "$apk") ($(wc -c < "$apk" | tr -d ' ') bytes)"

out=$(mktemp)
trap 'rm -f "$out"' EXIT
set +e
"$apksigner" verify --verbose --print-certs "$apk" > "$out" 2>&1
rc=$?
set -e
echo "apksigner exit code: $rc"
echo "----- apksigner verify --verbose --print-certs output -----"
cat "$out"
echo "----- end of apksigner output -----"
[ "$rc" -eq 0 ] || fail "APK signature does not verify: $apk"

check_output "$out" "$expected"
