#!/bin/bash
# Runs in the postgres container before docker-entrypoint.sh (compose.yaml; D98 rule 3c). In production it
# refuses to start PostgreSQL, failing closed at install time like the fiscal check, unless the recipients
# file mounted at /etc/restaurantpro/backup-recipients.txt (BACKUP_AGE_RECIPIENTS_FILE on the host, default
# deploy/backup-recipients.txt) holds at least two distinct age X25519 recipients, the operational and the
# recovery key, is not writable by group or others, and age accepts it. The host owner is checked by the
# backup scripts on the host. In test mode no recipients means plaintext WAL, with a WARNING.
set -euo pipefail
die() { echo "restaurantpro preflight: REFUSING TO START: $*" >&2; exit 1; }
# shellcheck disable=SC2034 # read by common.sh: there is no deploy/.env in here, compose passes the mode
DEPLOY_DIR=/nonexistent
# shellcheck source=lib/common.sh
. /etc/restaurantpro/common.sh
recipients=/etc/restaurantpro/backup-recipients.txt

env_mode
if production; then
  problem="$(recipients_problem "$recipients" 2 no-owner)"
  [[ -z "$problem" ]] || die "$problem (production, D98: WAL is only ever archived encrypted)"
elif [[ -z "$(recipient_keys "$recipients" 2>/dev/null)" ]]; then
  echo "restaurantpro preflight: WARNING: test mode without recipients: WAL is archived in PLAINTEXT (developer stacks only)" >&2
  exit 0
else
  problem="$(recipients_problem "$recipients" 1 no-owner)"
  [[ -z "$problem" ]] || die "$problem"
fi
age -R "$recipients" -o /dev/null </dev/null || die "age rejects $recipients"
echo "restaurantpro preflight: $RP_ENV mode, WAL is encrypted to $(recipient_keys "$recipients" | wc -l | tr -d ' ') recipients" >&2
