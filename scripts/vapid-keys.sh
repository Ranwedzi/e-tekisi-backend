#!/usr/bin/env sh
# Makes a VAPID key pair for push notifications (P-256), printed as the two
# lines to put in .env / .env.prod. Run it once per environment and keep the
# keys: replacing them breaks every existing subscription.
#
#   sh scripts/vapid-keys.sh
set -eu

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$dir/key.pem" 2>/dev/null

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# SEC1 DER: 30 77 02 01 01 04 20 <32-byte private scalar> ...
priv=$(openssl ec -in "$dir/key.pem" -outform DER 2>/dev/null | tail -c +8 | head -c 32 | b64url)
# SubjectPublicKeyInfo DER ends with the 65-byte uncompressed point.
pub=$(openssl ec -in "$dir/key.pem" -pubout -outform DER 2>/dev/null | tail -c 65 | b64url)

echo "VAPID_PUBLIC_KEY=$pub"
echo "VAPID_PRIVATE_KEY=$priv"
