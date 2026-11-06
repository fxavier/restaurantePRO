# shellcheck shell=bash
# The one way the scripts call age (D98 rule 6). Sourced, never run; the caller defines die().
# Uses the host's age when it is at least the pinned version, otherwise runs age from the pinned
#   restaurantpro-postgres image (deploy/postgres/Dockerfile), built here when missing, with
#   docker run --rm -i --network none, data on stdin/stdout and a key file bind-mounted read-only.
# Set RP_AGE_DOCKER=1 to force the image even when the host has age.

RP_AGE_IMAGE="restaurantpro-postgres:18.6-r1"
RP_AGE_PINNED="1.3.2"
RP_AGE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

version_at_least() { # $1 >= $2, both dotted numbers
  local IFS=. i a b
  read -r -a a <<<"$1"
  read -r -a b <<<"$2"
  for i in 0 1 2; do
    ((10#${a[i]:-0} > 10#${b[i]:-0})) && return 0
    ((10#${a[i]:-0} < 10#${b[i]:-0})) && return 1
  done
  return 0
}

age_on_host() {
  local v
  [[ "${RP_AGE_DOCKER:-}" != 1 ]] && command -v age >/dev/null && command -v age-keygen >/dev/null || return 1
  v="$(age --version 2>/dev/null | sed -E 's/^v//; s/[^0-9.].*$//')"
  [[ "$v" =~ ^[0-9]+(\.[0-9]+)*$ ]] && version_at_least "$v" "$RP_AGE_PINNED"
}

age_image_ready() {
  docker image inspect "$RP_AGE_IMAGE" >/dev/null 2>&1 && return 0
  echo "age: building $RP_AGE_IMAGE from $RP_AGE_LIB_DIR/../postgres" >&2
  docker build -q -t "$RP_AGE_IMAGE" "$RP_AGE_LIB_DIR/../postgres" >/dev/null || die "could not build $RP_AGE_IMAGE"
}

# $1 key file to mount read-only at /run/rp/key ("" for none), $2... the command (age or age-keygen) and its arguments.
age_in_image() {
  local key=$1 mount=()
  shift
  age_image_ready
  [[ -z "$key" ]] || mount=(-v "$(abs_path "$key"):/run/rp/key:ro")
  docker run --rm -i --network none ${mount[@]+"${mount[@]}"} --entrypoint "$1" "$RP_AGE_IMAGE" "${@:2}"
}

age_encrypt() { # $1 recipients file; stdin plaintext, stdout ciphertext
  if age_on_host; then age -R "$1"; else age_in_image "$1" age -R /run/rp/key; fi
}
age_decrypt() { # $1 identity file; stdin ciphertext, stdout plaintext
  if age_on_host; then age -d -i "$1"; else age_in_image "$1" age -d -i /run/rp/key; fi
}
age_public_key() { # $1 identity file; prints its recipient (age1...)
  if age_on_host; then age-keygen -y "$1"; else age_in_image "$1" age-keygen -y /run/rp/key; fi
}
age_keygen() { # prints a new identity (drills only; never on the server)
  if age_on_host; then age-keygen 2>/dev/null; else age_in_image "" age-keygen 2>/dev/null; fi
}
