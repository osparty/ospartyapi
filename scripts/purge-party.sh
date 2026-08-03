#!/usr/bin/env bash
#
# purge-party.sh — inspect and delete an OSParty party by host name, via the
# in-cluster Redis. Shows the party first, then asks before deleting.
#
#   ./purge-party.sh <hostname> [-n <namespace>] [-p <pod>]
#
# Deletes the same keys the API's PartyRepository.delete() does (party JSON,
# id index entry, host index, host credential, invite code). The websocket
# reconciler broadcasts the removal to live clients within ~5 seconds.

set -euo pipefail

NS="osparty"
POD="redis-0"
CONTAINER="redis"

usage() {
	echo "Usage: $(basename "$0") <hostname> [-n <namespace>] [-p <pod>]" >&2
	exit 64
}

HOSTNAME_ARG=""
while [ $# -gt 0 ]; do
	case "$1" in
		-n) NS="$2"; shift 2 ;;
		-p) POD="$2"; shift 2 ;;
		-h|--help) usage ;;
		*)
			[ -n "$HOSTNAME_ARG" ] && usage
			HOSTNAME_ARG="$1"; shift ;;
	esac
done
[ -n "$HOSTNAME_ARG" ] || usage

# Colours only when talking to a terminal.
if [ -t 1 ]; then
	BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
	YELLOW=$'\033[33m'; CYAN=$'\033[36m'; RESET=$'\033[0m'
else
	BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; CYAN=""; RESET=""
fi

die() { echo "${RED}✗ $*${RESET}" >&2; exit 1; }

rcli() {
	kubectl exec -n "$NS" "$POD" -c "$CONTAINER" -- redis-cli "$@"
}

# Normalise like PartyFactory.normalizeHost(): NBSP -> space, trim, lowercase.
HOST_KEY=$(printf '%s' "$HOSTNAME_ARG" \
	| sed -e 's/\xc2\xa0/ /g' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
	| tr '[:upper:]' '[:lower:]')

echo "${DIM}Looking up party for host '${HOST_KEY}' in ${NS}/${POD}…${RESET}"

PARTY_ID=$(rcli GET "partyhost:${HOST_KEY}")
[ -n "$PARTY_ID" ] || die "No party found for host '${HOST_KEY}'."

PARTY_JSON=$(rcli GET "party:${PARTY_ID}")
[ -n "$PARTY_JSON" ] || die "Host index points at party '${PARTY_ID}' but the party key is gone (nothing to clean: the index key expires on its own TTL)."

# ---- pretty-print ----------------------------------------------------------

field() { # field <jq-expr> — empty string when jq is missing or null
	if command -v jq >/dev/null 2>&1; then
		printf '%s' "$PARTY_JSON" | jq -r "$1 // empty" 2>/dev/null || true
	fi
}

echo
echo "${BOLD}${CYAN}── Party ${PARTY_ID} ───────────────────────────────${RESET}"
if command -v jq >/dev/null 2>&1; then
	CREATED_MS=$(field '.createdAt')
	CREATED_HUMAN=""
	if [ -n "$CREATED_MS" ]; then
		CREATED_SECS=$((CREATED_MS / 1000))
		AGE_MIN=$((($(date +%s) - CREATED_SECS) / 60))
		# BSD date (mac) vs GNU date
		CREATED_HUMAN=$(date -r "$CREATED_SECS" '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
			|| date -d "@${CREATED_SECS}" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$CREATED_MS")
		CREATED_HUMAN="${CREATED_HUMAN} (${AGE_MIN} min ago)"
	fi
	printf "${BOLD}%-14s${RESET} %s\n" \
		"Host"        "$(field '.host')" \
		"Activity"    "$(field '.activity')$( [ "$(field '.hardMode')" = "true" ] && echo ' (hard mode)')" \
		"Size"        "$(field '.size')/$(field '.capacity')" \
		"World"       "$(field '.world')" \
		"Created"     "$CREATED_HUMAN" \
		"Invite code" "$(field '.inviteCode')" \
		"Loot"        "$(field '.lootRule')" \
		"Private"     "$(field '.privateParty')" \
		"Description" "$(field '.description')" \
		"Members"     "$(field '[.members[]?.name] | join(", ")')"
	VOICE=$(field '.discordChannelId')
	[ -n "$VOICE" ] && printf "${BOLD}%-14s${RESET} %s\n" "Voice channel" "$VOICE"
	echo
	echo "${DIM}Full record:${RESET}"
	printf '%s\n' "$PARTY_JSON" | jq .
else
	echo "${YELLOW}(jq not found — showing raw JSON)${RESET}"
	printf '%s\n' "$PARTY_JSON"
fi
echo "${BOLD}${CYAN}────────────────────────────────────────────────────${RESET}"
echo

# ---- confirm & clean up ----------------------------------------------------

printf "${BOLD}Delete this party and all its keys? [y/N] ${RESET}"
read -r ANSWER
case "$ANSWER" in
	y|Y|yes|YES)
		;;
	*)
		echo "${YELLOW}Cancelled — nothing deleted.${RESET}"
		exit 0 ;;
esac

INVITE_CODE=$(field '.inviteCode')
if [ -z "$INVITE_CODE" ] && ! command -v jq >/dev/null 2>&1; then
	# jq-less fallback: pull the invite code straight out of the JSON.
	INVITE_CODE=$(printf '%s' "$PARTY_JSON" \
		| sed -n 's/.*"inviteCode":"\([^"]*\)".*/\1/p')
fi

echo "${DIM}Deleting keys…${RESET}"
rcli DEL "party:${PARTY_ID}" "partykey:${PARTY_ID}" "partyhost:${HOST_KEY}" >/dev/null
rcli SREM "party:ids" "$PARTY_ID" >/dev/null
if [ -n "$INVITE_CODE" ]; then
	rcli DEL "partycode:${INVITE_CODE}" >/dev/null
fi

echo "${GREEN}✓ Party ${PARTY_ID} (host '${HOST_KEY}') deleted."
echo "  Live clients will see it disappear on the next reconcile pass (~5s).${RESET}"
