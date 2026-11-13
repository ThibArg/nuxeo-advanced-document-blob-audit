#!/usr/bin/env bash
# Helpers for runbook-by-agents.md. Source it at the start of EVERY shell command, from the root of
# the repository:
#
#   source test-campaign/agent/blobaudit-helpers.sh
#
# Requires bash, curl and jq. Reads these variables (set them before sourcing, or export them):
#   NX            Nuxeo base URL                         default http://localhost:8080/nuxeo
#   NX_ADMIN      administrator login                    default Administrator
#   NX_ADMIN_PWD  administrator password                 required, never printed
#   TEST_PWD      password of alice, bob and carol       required, never printed
#   FILES         the campaign folder                    default: the parent of this file's folder
#   RUN           id of the run, suffix of every name    default: read from $FILES/results/current-run
#   STEP          id of the current step, for the log    set it before each step
# Nothing here prints a password; keep it that way.

# Optional private settings (NX, NX_ADMIN_PWD, TEST_PWD...), outside the results: mode 600, never shared.
# shellcheck disable=SC1091
[ -f "$HOME/.config/blobaudit-test.env" ] && source "$HOME/.config/blobaudit-test.env"

: "${NX:=http://localhost:8080/nuxeo}"
: "${NX_ADMIN:=Administrator}"
: "${FILES:=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)}"
: "${WS:=/default-domain/workspaces/blob-audit-tests}"
: "${RESTRICTED:=$WS/restricted}"
: "${AUDITOR:=carol}"
: "${POLL_SECONDS:=90}"
: "${SILENCE_SECONDS:=15}"

RESULTS_DIR="$FILES/results"
mkdir -p "$RESULTS_DIR"
if [ -z "${RUN:-}" ] && [ -f "$RESULTS_DIR/current-run" ]; then
    RUN=$(cat "$RESULTS_DIR/current-run")
fi
if [ -n "${RUN:-}" ]; then
    RAW="$RESULTS_DIR/raw-$RUN"
    LOG="$RESULTS_DIR/agent-run-$RUN.tsv"
    STATE="$RESULTS_DIR/state-$RUN.env"
    mkdir -p "$RAW"
    # Document ids and counters saved by earlier commands of the same run.
    # shellcheck disable=SC1090
    [ -f "$STATE" ] && source "$STATE"
fi

# Starts a new run: a fresh suffix for the document names, a fresh log.
new_run() {
    RUN=$(date -u +%Y%m%d-%H%M%S)
    RAW="$RESULTS_DIR/raw-$RUN"
    LOG="$RESULTS_DIR/agent-run-$RUN.tsv"
    STATE="$RESULTS_DIR/state-$RUN.env"
    mkdir -p "$RAW"
    printf '%s' "$RUN" >"$RESULTS_DIR/current-run"
    printf 'step\tverdict\tdetail\n' >"$LOG"
    : >"$STATE"
    echo "RUN=$RUN"
}

# Saves a variable (a document id...) for the next commands of the run: save S01 <uid>
save() {
    printf '%s=%q\n' "$1" "$2" >>"$STATE"
    printf -v "$1" '%s' "$2"
}

_pwd_of() {
    if [ "$1" = "$NX_ADMIN" ]; then
        printf '%s' "${NX_ADMIN_PWD:?NX_ADMIN_PWD is not set}"
    else
        printf '%s' "${TEST_PWD:?TEST_PWD is not set}"
    fi
}

# nx USER METHOD PATH [curl args...]: the response body.
nx() {
    local u=$1 m=$2 p=$3
    shift 3
    curl -sS -u "$u:$(_pwd_of "$u")" -X "$m" "$NX$p" -H 'Accept: application/json' "$@"
}

# nxcode USER METHOD PATH [curl args...]: the HTTP status only.
nxcode() {
    local u=$1 m=$2 p=$3
    shift 3
    curl -sS -o /dev/null -w '%{http_code}' -u "$u:$(_pwd_of "$u")" -X "$m" "$NX$p" "$@"
}

# op USER OPERATION INPUT [PARAMS_JSON]: runs an Automation operation. INPUT: "doc:<id or path>" or "".
op() {
    local u=$1 o=$2 in=$3 params=${4:-}
    [ -n "$params" ] || params='{}'
    nx "$u" POST "/api/v1/automation/$o" -H 'Content-Type: application/json' -d "$(_op_body "$in" "$params")"
}

opcode() {
    local u=$1 o=$2 in=$3 params=${4:-}
    [ -n "$params" ] || params='{}'
    nxcode "$u" POST "/api/v1/automation/$o" -H 'Content-Type: application/json' -d "$(_op_body "$in" "$params")"
}

_op_body() {
    if [ -n "$1" ]; then
        jq -cn --arg i "$1" --argjson p "$2" '{input: $i, params: $p}'
    else
        jq -cn --argjson p "$2" '{params: $p}'
    fi
}

# mime_of RELATIVE_PATH: the mime type MANIFEST.tsv gives for a test file.
mime_of() {
    awk -F'\t' -v p="$1" '$1 == p { print $4 }' "$FILES/MANIFEST.tsv"
}

# upload USER RELATIVE_PATH [MIME]: uploads one file in a new batch, prints the batch id.
upload() {
    local u=$1 rel=$2 mime=${3:-} batch
    _ensure_file "$rel" || return 1
    [ -n "$mime" ] || mime=$(mime_of "$rel")
    batch=$(nx "$u" POST /api/v1/upload/ | jq -r .batchId)
    nx "$u" POST "/api/v1/upload/$batch/0" -H "X-File-Name: $(basename "$rel")" -H "X-File-Type: $mime" \
        -H 'Content-Type: application/octet-stream' --data-binary @"$FILES/$rel" >/dev/null
    printf '%s' "$batch"
}

# _ensure_file RELATIVE_PATH: the two S16 files over 10 MB are not in git; they are written on first use.
_ensure_file() {
    [ -f "$FILES/$1" ] && return 0
    bash "$FILES/_generator/make-large-files.sh" >&2
    [ -f "$FILES/$1" ] || { echo "missing test file: $FILES/$1" >&2; return 1; }
}

# ==================== Documents ====================

# create_doc USER PARENT_PATH NAME TITLE [RELATIVE_PATH] [TYPE] [MIME]: prints the uid ("null" on failure).
create_doc() {
    local u=$1 parent=$2 name=$3 title=$4 rel=${5:-} type=${6:-File} mime=${7:-} props batch
    if [ -n "$rel" ]; then
        batch=$(upload "$u" "$rel" "$mime")
        props=$(jq -cn --arg t "$title" --arg b "$batch" \
            '{"dc:title": $t, "file:content": {"upload-batch": $b, "upload-fileId": "0"}}')
    else
        props=$(jq -cn --arg t "$title" '{"dc:title": $t}')
    fi
    nx "$u" POST "/api/v1/path$parent" -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg ty "$type" --arg n "$name" --argjson p "$props" \
            '{"entity-type": "document", type: $ty, name: $n, properties: $p}')" | jq -r .uid
}

# replace USER DOC_ID RELATIVE_PATH [MIME]: replaces file:content, like the Web UI Replace action.
replace() {
    local u=$1 id=$2 rel=$3 mime=${4:-} batch
    batch=$(upload "$u" "$rel" "$mime")
    nx "$u" PUT "/api/v1/id/$id" -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg b "$batch" \
            '{"entity-type": "document", properties: {"file:content": {"upload-batch": $b, "upload-fileId": "0"}}}')" \
        | jq -r '.uid // .message'
}

# set_prop USER DOC_ID XPATH VALUE: sets one string property and saves.
set_prop() {
    nx "$1" PUT "/api/v1/id/$2" -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg k "$3" --arg v "$4" '{"entity-type": "document", properties: {($k): $v}}')" \
        | jq -r '.uid // .message'
}

# attach_at USER DOC_ID RELATIVE_PATH XPATH: Blob.AttachOnDocument. XPATH "files:files" appends an
# attachment, "files:files/0/file" replaces the first one.
attach_at() {
    local u=$1 id=$2 rel=$3 xp=$4 batch
    batch=$(upload "$u" "$rel")
    nxcode "$u" POST "/api/v1/upload/$batch/0/execute/Blob.AttachOnDocument" -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg d "$id" --arg x "$xp" '{params: {document: $d, xpath: $x, save: true}}')"
    echo
}

# remove_at USER DOC_ID XPATH: Blob.RemoveFromDocument. "files:files/1" removes the second attachment.
remove_at() {
    opcode "$1" Blob.RemoveFromDocument "doc:$2" "$(jq -cn --arg x "$3" '{xpath: $x, save: true}')"
    echo
}

# version USER DOC_ID: Document.CreateVersion, Minor, exactly what the Web UI button runs. Prints the
# version label of the live document afterwards.
version() {
    nx "$1" POST "/api/v1/id/$2/@op/Document.CreateVersion" -H 'Content-Type: application/json' \
        -d '{"params": {"increment": "Minor", "saveDocument": true}}' >/dev/null
    label "$1" "$2"
}

# label USER DOC_ID: the version label of a document ("0.2", "0.2+"). LTS 2025 only writes versionLabel
# in the document JSON when asked with the fetch-document header.
label() {
    nx "$1" GET "/api/v1/id/$2" -H 'fetch-document: versionLabel' | jq -r '.versionLabel'
}

# ==================== Reading the results ====================

# audit_entries DOC_ID: the blobContentModified entries of the document (read as administrator).
audit_entries() {
    nx "$NX_ADMIN" GET "/api/v1/id/$1/@audit?eventId=blobContentModified&pageSize=200" | jq -c '.entries // []'
}

audit_count() {
    audit_entries "$1" | jq 'length'
}

# wait_audit DOC_ID COUNT: waits until the document has COUNT entries, prints the newest one.
wait_audit() {
    local id=$1 want=$2 i out n=0
    for ((i = 0; i < POLL_SECONDS; i += 3)); do
        out=$(audit_entries "$id")
        n=$(jq 'length' <<<"$out")
        if [ "$n" -ge "$want" ]; then
            jq -c 'max_by(.id)' <<<"$out"
            return 0
        fi
        sleep 3
    done
    echo "timeout: $n blobContentModified entries on $id, expected $want" >&2
    return 1
}

# diffs DOC_ID: the BlobDiff documents of a source, read by the auditor (repository query, synchronous).
diffs() {
    nx "$AUDITOR" GET "/api/v1/search/pp/BLOB_DIFFS_FOR_DOCUMENT/execute?queryParams=$1&pageSize=100" \
        -H 'properties: blobdiff,dublincore' | jq -c '.entries // []'
}

# wait_diff CORRELATION_ID DOC_ID [NOT_UID]: waits for the BlobDiff of that correlation id (other than
# NOT_UID, for a retry), prints it.
wait_diff() {
    local cid=$1 id=$2 not=${3:-} i d
    for ((i = 0; i < POLL_SECONDS; i += 3)); do
        d=$(diffs "$id" | jq -c --arg c "$cid" --arg n "$not" \
            '[.[] | select(.properties["bdiff:correlationId"] == $c and .uid != $n)] | first // empty')
        if [ -n "$d" ]; then
            echo "$d"
            return 0
        fi
        sleep 3
    done
    echo "timeout: no BlobDiff with correlation id $cid" >&2
    return 1
}

# diff_body DIFF_ID: the text of bdiff:diff (empty when the diff has no body).
diff_body() {
    curl -sS -f -u "$AUDITOR:$(_pwd_of "$AUDITOR")" "$NX/api/v1/id/$1/@blob/bdiff:diff" 2>/dev/null
}

# ==================== Checks ====================

# verdict PASS|FAIL|INFO|FINDING DETAIL: prints and logs one line for the current $STEP.
verdict() {
    printf '%s\t%s\t%s\n' "${STEP:-?}" "$1" "$2" | tee -a "${LOG:-/dev/null}"
}

_keep() {
    [ -n "${RAW:-}" ] && printf '%s\n' "$2" >"$RAW/${STEP:-step}-$1"
}

# expect DOC_ID COUNT_BEFORE KIND [ARGS]: checks the outcome of the version just created.
#   none                 no new blobContentModified entry within SILENCE_SECONDS
#   ok ADDED REMOVED CHANGED   new entry with diffCorrelationId, BlobDiff "ok" with these counters
#   skip REASON          new entry with skipReason REASON and no diffCorrelationId, no BlobDiff
#   status STATUS        new entry with diffCorrelationId, BlobDiff with bdiff:status STATUS
# Sets LAST_ENTRY, LAST_DIFF, LAST_DIFF_ID, LAST_BODY for the checks below.
expect() {
    local id=$1 before=$2 kind=$3 cid reason st counters n
    shift 3
    LAST_ENTRY="" LAST_DIFF="" LAST_DIFF_ID="" LAST_BODY=""
    if [ "$kind" = none ]; then
        sleep "$SILENCE_SECONDS"
        n=$(audit_count "$id")
        if [ "$n" -eq "$before" ]; then
            verdict PASS "no audit entry, as expected"
        else
            verdict FAIL "expected no audit entry, got $((n - before)) new: $(audit_entries "$id" | jq -c 'max_by(.id) | .comment')"
        fi
        return
    fi
    if ! LAST_ENTRY=$(wait_audit "$id" $((before + 1))); then
        verdict FAIL "no new blobContentModified entry (expected $kind $*)"
        return
    fi
    _keep audit.json "$LAST_ENTRY"
    cid=$(jq -r '.extended.diffCorrelationId // empty' <<<"$LAST_ENTRY")
    reason=$(jq -r '.extended.skipReason // empty' <<<"$LAST_ENTRY")
    if [ "$kind" = skip ]; then
        if [ "$reason" = "$1" ] && [ -z "$cid" ]; then
            verdict PASS "skip $reason, comment: $(jq -r .comment <<<"$LAST_ENTRY")"
        else
            verdict FAIL "expected skip $1, got skipReason='$reason' diffCorrelationId='$cid'"
        fi
        return
    fi
    if [ -z "$cid" ]; then
        verdict FAIL "expected a diff ($kind $*), got skipReason='$reason'"
        return
    fi
    if ! LAST_DIFF=$(wait_diff "$cid" "$id"); then
        verdict FAIL "audit entry has correlation id $cid but no BlobDiff appeared"
        return
    fi
    _keep diff.json "$LAST_DIFF"
    LAST_DIFF_ID=$(jq -r .uid <<<"$LAST_DIFF")
    if [ "$(jq -r '.properties["bdiff:diff"] != null' <<<"$LAST_DIFF")" = true ]; then
        LAST_BODY=$(diff_body "$LAST_DIFF_ID")
        _keep body.txt "$LAST_BODY"
    fi
    st=$(jq -r '.properties["bdiff:status"]' <<<"$LAST_DIFF")
    counters=$(jq -r '[.properties["bdiff:added"], .properties["bdiff:removed"], .properties["bdiff:changed"]]
        | map(. // 0 | tostring) | join("/")' <<<"$LAST_DIFF")
    case $kind in
    ok)
        if [ "$st" = ok ] && [ "$counters" = "$1/$2/$3" ]; then
            verdict PASS "ok $counters, $(jq -r '.properties["bdiff:summary"]' <<<"$LAST_DIFF")"
        else
            verdict FAIL "expected ok $1/$2/$3 (added/removed/changed), got $st $counters: $(jq -r '.properties["bdiff:summary"]' <<<"$LAST_DIFF")"
        fi
        ;;
    status)
        if [ "$st" = "$1" ]; then
            verdict PASS "status $st, counters $counters"
        else
            verdict FAIL "expected status $1, got $st $counters"
        fi
        ;;
    esac
}

# observe DOC_ID COUNT_BEFORE: for a probe, records what happened without judging it.
observe() {
    local id=$1 before=$2 cid reason
    LAST_ENTRY="" LAST_DIFF="" LAST_DIFF_ID="" LAST_BODY=""
    if ! LAST_ENTRY=$(wait_audit "$id" $((before + 1)) 2>/dev/null); then
        verdict INFO "observed: no audit entry"
        return
    fi
    _keep audit.json "$LAST_ENTRY"
    cid=$(jq -r '.extended.diffCorrelationId // empty' <<<"$LAST_ENTRY")
    reason=$(jq -r '.extended.skipReason // empty' <<<"$LAST_ENTRY")
    if [ -n "$reason" ]; then
        verdict INFO "observed: skip $reason, mime $(jq -r '"\(.extended.oldMimeType) -> \(.extended.newMimeType)"' <<<"$LAST_ENTRY")"
        return
    fi
    LAST_DIFF=$(wait_diff "$cid" "$id") || { verdict INFO "observed: correlation id but no BlobDiff"; return; }
    _keep diff.json "$LAST_DIFF"
    LAST_DIFF_ID=$(jq -r .uid <<<"$LAST_DIFF")
    verdict INFO "observed: $(jq -r '.properties | "\(.["bdiff:status"]) \(.["bdiff:added"])/\(.["bdiff:removed"])/\(.["bdiff:changed"]) \(.["bdiff:summary"]), mime \(.["bdiff:oldMimeType"]) -> \(.["bdiff:newMimeType"])"' <<<"$LAST_DIFF")"
}

# body_has TEXT / body_lacks TEXT: a literal substring of the last diff body.
body_has() {
    if grep -qF -- "$1" <<<"$LAST_BODY"; then verdict PASS "body has: $1"; else verdict FAIL "body lacks: $1"; fi
}

body_lacks() {
    if grep -qF -- "$1" <<<"$LAST_BODY"; then verdict FAIL "body should not have: $1"; else verdict PASS "body lacks, as expected: $1"; fi
}

# prop_is KEY VALUE: a property of the last BlobDiff (bdiff:truncated, bdiff:xpath, bdiff:user...).
prop_is() {
    local v
    v=$(jq -r --arg k "$1" '.properties[$k] | tostring' <<<"$LAST_DIFF")
    if [ "$v" = "$2" ]; then verdict PASS "$1 = $v"; else verdict FAIL "$1 = $v, expected $2"; fi
}

# similarity_in LOW HIGH: bdiff:similarity of the last BlobDiff within [LOW, HIGH].
similarity_in() {
    local s
    s=$(jq -r '.properties["bdiff:similarity"] // "absent"' <<<"$LAST_DIFF")
    if [ "$s" != absent ] && awk -v s="$s" -v lo="$1" -v hi="$2" 'BEGIN { exit !(s >= lo && s <= hi) }'; then
        verdict PASS "similarity $s in [$1, $2]"
    else
        verdict FAIL "similarity $s, expected in [$1, $2]"
    fi
}

# entry_has KEY VALUE: an extended info of the last audit entry.
entry_has() {
    local v
    v=$(jq -r --arg k "$1" '.extended[$k] // "absent"' <<<"$LAST_ENTRY")
    if [ "$v" = "$2" ]; then verdict PASS "audit $1 = $v"; else verdict FAIL "audit $1 = $v, expected $2"; fi
}

# check DESCRIPTION EXPECTED ACTUAL: generic equality check.
check() {
    if [ "$2" = "$3" ]; then verdict PASS "$1: $3"; else verdict FAIL "$1: got '$3', expected '$2'"; fi
}

# denied DESCRIPTION HTTP_STATUS: an access that must be refused (403, or 404 when hidden).
denied() {
    case "$2" in
    403 | 404) verdict PASS "$1: refused ($2)" ;;
    *) verdict FAIL "$1: got $2, expected 403 or 404" ;;
    esac
}

# total_is DOC_ID COUNT: the number of blobContentModified entries of a document, at the end of a scenario.
total_is() {
    check "total blobContentModified entries" "$2" "$(audit_count "$1")"
}

# source_state DIFF_ID: the blobDiffSource enricher of a BlobDiff, as the auditor sees it.
source_state() {
    nx "$AUDITOR" GET "/api/v1/id/$1" -H 'enrichers-document: blobDiffSource' | jq -c '.contextParameters.blobDiffSource'
}

# tomorrow_utc: the day after today, UTC, yyyy-MM-dd (BSD or GNU date).
tomorrow_utc() {
    date -u -v+1d +%Y-%m-%d 2>/dev/null || date -u -d tomorrow +%Y-%m-%d
}
