#!/usr/bin/env bash
# Tests the parser in scripts/check-cert.sh on saved `apksigner verify --verbose --print-certs` outputs in
# scripts/testdata. No Android SDK or APK needed.
#
# apksigner-signer-number.txt is real output for an APK signed with a throwaway test key (public fingerprint below);
# the other files are derived from it: apksigner's "Signer (minSdkVersion=..., maxSdkVersion=...)" format, no
# certificate digest line, and a second signer with a different certificate.
set -uo pipefail
cd "$(dirname "$0")/.."

FP=c0e3c8e8dac9ac4a7344dfa17b3301cf20c57e69db56e91765bfba4205ab4bf5
FP_COLONS=$(printf '%s' "$FP" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
WRONG=1111111111111111111111111111111111111111111111111111111111111111
DATA=scripts/testdata

failures=0
expect() {
  local want=$1 name=$2 file=$3 fp=$4 got out
  if out=$(scripts/check-cert.sh --from-output "$DATA/$file" "$fp" 2>&1); then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then
    printf 'ok    %-52s (expected %s)\n' "$name" "$want"
  else
    printf 'FAIL  %-52s expected %s, got %s\n' "$name" "$want" "$got"
    printf '%s\n' "$out" | sed 's/^/      /'
    failures=$((failures + 1))
  fi
}

expect pass "Signer #1 format"                                   apksigner-signer-number.txt    "$FP"
expect pass "Signer (minSdkVersion=..., maxSdkVersion=...) format" apksigner-signer-sdk-range.txt "$FP"
expect pass "expected value with colons and upper case"          apksigner-signer-number.txt    "$FP_COLONS"
expect fail "no certificate digest line"                         apksigner-no-digest.txt        "$FP"
expect fail "wrong fingerprint"                                  apksigner-signer-number.txt    "$WRONG"
expect fail "two signers with different certificates"            apksigner-two-signers.txt      "$FP"
expect fail "expected value is not a SHA-256"                    apksigner-signer-number.txt    "not-a-fingerprint"

if [ "$failures" -eq 0 ]; then echo "All check-cert tests passed."; else echo "$failures test(s) failed."; exit 1; fi
