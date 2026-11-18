#!/usr/bin/env bash
# Sets this repo's four signing secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD,
# KEY_ALIAS, KEY_PASSWORD) from the launcher's release keystore, using gh.
#
#   ./scripts/set-signing-secrets.sh [path/to/keystore.jks]
#
# Prompts for the passwords without echoing them. Before setting anything it
# checks that the key's certificate is the launcher's release certificate (the
# one the updater on phones is signed with), so a wrong keystore or alias can't
# end up in the repo. Values go to gh on stdin, never as command-line arguments.

set -euo pipefail

REPO="${REPO:-Offline-DC/updater}"
# SHA-256 of the signing certificate on the updater inside launcher v6.60.10.
EXPECTED_CERT="c52199c1093690a9776b38f629a7fcd8845ab1813325a67c916f38689905a9b7"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v gh >/dev/null      || die "gh not installed (brew install gh)"
command -v keytool >/dev/null || die "keytool not found (it comes with a JDK: brew install openjdk)"
gh auth status >/dev/null 2>&1 || die "gh not signed in (gh auth login)"

KEYSTORE="${1:-}"
if [[ -z "$KEYSTORE" ]]; then
  read -r -p "Path to the launcher release keystore (.jks): " KEYSTORE
fi
KEYSTORE="${KEYSTORE/#\~/$HOME}"
[[ -f "$KEYSTORE" ]] || die "no file at $KEYSTORE"

read -r -s -p "Keystore password: " KS_PASS; echo
read -r    -p "Key alias: " KEY_ALIAS
read -r -s -p "Key password (Enter if same as keystore password): " KEY_PASS; echo
[[ -n "$KEY_PASS" ]] || KEY_PASS="$KS_PASS"
[[ -n "$KS_PASS" && -n "$KEY_ALIAS" ]] || die "keystore password and alias are required"

# Check the alias exists and its cert is the launcher's. keytool reads the
# password from the environment (-storepass:env), not from argv.
export KS_PASS
LISTING=$(keytool -list -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -storepass:env KS_PASS 2>&1) \
  || die "keytool couldn't open alias '$KEY_ALIAS' with that password:
$LISTING"
CERT=$(printf '%s\n' "$LISTING" | grep -m1 -i 'SHA256:' | sed 's/.*SHA256: *//' | tr -d ':' | tr 'A-F' 'a-f')
[[ "$CERT" == "$EXPECTED_CERT" ]] || die "certificate doesn't match the launcher's release key.
  expected: $EXPECTED_CERT
  got:      ${CERT:-none}
Wrong keystore or alias. Nothing was set."
echo "Certificate matches the launcher's release key."

# Also check the key password actually unlocks the key (a signing failure in CI
# would otherwise be the first sign of a wrong one).
export KEY_PASS
TMPKS=$(mktemp -d)/check.p12
keytool -importkeystore -srckeystore "$KEYSTORE" -srcstorepass:env KS_PASS \
  -srcalias "$KEY_ALIAS" -srckeypass:env KEY_PASS \
  -destkeystore "$TMPKS" -deststoretype PKCS12 -deststorepass:env KS_PASS -destkeypass:env KS_PASS \
  -noprompt >/dev/null 2>&1 || { rm -rf "$(dirname "$TMPKS")"; die "the key password doesn't unlock alias '$KEY_ALIAS'. Nothing was set."; }
rm -rf "$(dirname "$TMPKS")"
echo "Key password unlocks the key."

echo
read -r -p "Set KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD on $REPO? [y/N] " OK
[[ "$OK" == [yY]* ]] || die "cancelled. Nothing was set."

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$KS_PASS"   | gh secret set KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set KEY_ALIAS -R "$REPO"
printf '%s' "$KEY_PASS"  | gh secret set KEY_PASSWORD -R "$REPO"
unset KS_PASS KEY_PASS

echo
gh secret list -R "$REPO"
echo
echo "Done. Next: git push origin main (in the updater repo) to publish v<versionCode>,"
echo "or re-run the Release workflow if main is already pushed:"
echo "  gh workflow run release.yml -R $REPO"
