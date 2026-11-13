# Blob audit — runbook for an agent

You are an agent testing the Nuxeo plugin `nuxeo-advanced-document-blob-audit` end to end, over the
REST API of a **local** Docker Compose deployment (Nuxeo LTS 2025 + MongoDB + OpenSearch). You check
that users and containers exist (and create them if needed), upload the test files, create versions,
replace files, read the audit entries and the `BlobDiff` documents, and report.

The human version of this campaign is `runbook.md`, in the same folder; the expected results are the
same. Read its § 2.1 for the vocabulary.

## 0. Rules

1. **Never** modify the repository (plugin code, runbooks, helpers, test files), Nuxeo, Web UI or the
   Docker files: you write under `test-campaign/results/` only, which git ignores. Never run the
   Maven build or the unit tests. You only call the REST API of the local instance.
2. **Never** print, log or write a password. The helpers read them from environment variables.
3. Work on `http://localhost` only. If `NX` points elsewhere, stop and ask.
4. Every document you create carries the run id (`$RUN`) in its name: a second run never collides
   with the first. Do not delete what earlier runs created.
5. A failed check is **not** a reason to stop: log it (the helpers do), keep the raw responses, go
   on with the next step. Stop only on the conditions of § 2.
6. A **probe** records an outcome with `observe` (`INFO`) instead of checking it; compare it with the
   expectation given and, when it differs, log it with `verdict FINDING "…"`. There is none at the
   moment: S11 `-03`, P1 and P2 were probes until the plugin read `text/rtf` and `application/yaml`
   (plugin items 106 and 107); they are regular checks now.
7. Run every shell command from the **root of the repository**, and start it with
   `source test-campaign/agent/blobaudit-helpers.sh`: it reloads the run id, the document ids saved
   with `save`, and the functions.
8. The blocks are **bash** (3.2 is enough). If your shell is another one, run each block through
   `bash <<'EOF' … EOF`. Run one block per command, with a command timeout of **10 minutes**: the
   waits are built in (up to `POLL_SECONDS` per outcome, `SILENCE_SECONDS` per "nothing happens").

## 1. Inputs

| Variable | Default | Meaning |
|---|---|---|
| `NX` | `http://localhost:8080/nuxeo` | Nuxeo URL |
| `NX_ADMIN` | `Administrator` | administrator login |
| `NX_ADMIN_PWD` | none, **required** | administrator password (ask the user if not set) |
| `TEST_PWD` | none, **required** | password given to `alice`, `bob`, `carol` (local test users) |
| `FILES` | `test-campaign/` (found by the helpers) | test files, `MANIFEST.tsv` (mime type of each file) |
| `WS` | `/default-domain/workspaces/blob-audit-tests` | test workspace |
| `RESTRICTED` | `$WS/restricted` | folder the auditor cannot read |
| `POLL_SECONDS` / `SILENCE_SECONDS` | `90` / `15` | how long to wait for an outcome / for the absence of one |

Each command may run in a fresh shell: the variables must survive. Either the user exports them
before starting you, or they live in `~/.config/blobaudit-test.env` (`export NX_ADMIN_PWD=…` lines,
mode `600`), which the helpers source when it exists. Never write them anywhere else. If neither is
available, ask the user.

Tools: `bash`, `curl`, `jq`, `awk`. The functions are in `agent/blobaudit-helpers.sh`; read its
header once. Main ones:

| Function | Does |
|---|---|
| `new_run` | starts a run: new `$RUN`, new log `results/agent-run-$RUN.tsv` |
| `save NAME VALUE` | keeps a value (a document id) for the next commands of the run |
| `create_doc USER PARENT NAME TITLE [FILE] [TYPE] [MIME]` | creates a document, prints its uid |
| `replace USER ID FILE [MIME]` | replaces `file:content` (the Web UI **Replace** action) |
| `attach_at USER ID FILE XPATH` / `remove_at USER ID XPATH` | attachments (`files:files`) |
| `set_prop USER ID XPATH VALUE` | sets a string property |
| `version USER ID` | `Document.CreateVersion` Minor (the Web UI **Create Version** button), prints the label |
| `label USER ID` | the version label of a document (`0.2`, `0.2+`); LTS 2025 sends it only with the `fetch-document: versionLabel` header, which `label` adds |
| `audit_count ID` | number of `blobContentModified` entries of a document |
| `expect ID COUNT_BEFORE none \| ok A R C \| skip REASON \| status STATUS` | waits for and checks the outcome of the version just created; `A R C` = added, removed, changed |
| `observe ID COUNT_BEFORE` | records the outcome of a probe |
| `body_has TEXT`, `body_lacks TEXT`, `prop_is KEY VALUE`, `entry_has KEY VALUE`, `similarity_in LOW HIGH`, `check DESC EXPECTED ACTUAL`, `denied DESC HTTP_STATUS`, `total_is ID N` | checks on the last outcome; `denied` accepts 403 or 404 |
| `op` / `opcode USER OPERATION INPUT PARAMS_JSON` | runs an Automation operation (body / HTTP status) |
| `diffs ID`, `diff_body DIFF_ID`, `source_state DIFF_ID`, `tomorrow_utc` | the `BlobDiff`s of a source, a diff body, the `blobDiffSource` enricher, the purge boundary |

Everything a run writes goes under `test-campaign/results/`, which git ignores: `current-run`,
`agent-run-$RUN.tsv` (one line per check: step, `PASS` / `FAIL` / `INFO` / `FINDING`, detail),
`state-$RUN.env` (the saved ids), `raw-$RUN/` (audit entry, `BlobDiff` JSON and body of each step).

The two S16 files over 10 MB are not in git either: the first upload that needs them runs
`_generator/make-large-files.sh`, which writes them and checks their SHA-256 against `MANIFEST.tsv`.

`expect` sets `LAST_ENTRY` (audit entry JSON), `LAST_DIFF` (`BlobDiff` JSON), `LAST_DIFF_ID`,
`LAST_BODY`, and keeps them under `results/raw-$RUN/`. Set `STEP=…` before each `expect`: it is the
first column of the log.

The step pattern, used everywhere below:

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=S01-01; B=$(audit_count "$S01")          # entries before the step
replace alice "$S01" S01-word-contract/contract-01.docx
version alice "$S01"                           # prints 0.2
expect "$S01" "$B" ok 1 0 1                    # 1 addition, 0 deletion, 1 modification
body_has "+ The notice must be sent by registered letter."
```

## 2. Preflight — stop conditions

```bash
source test-campaign/agent/blobaudit-helpers.sh
case "$NX" in http://localhost*|http://127.0.0.1*) echo "local: ok" ;; *) echo "STOP: not local" ;; esac
curl -s -o /dev/null -w 'runningstatus %{http_code}\n' "$NX/runningstatus"                      # 200
echo "admin login $(nxcode "$NX_ADMIN" GET /api/v1/me)"                                           # 200
echo "plugin operations $(nxcode "$NX_ADMIN" GET /api/v1/automation/BlobDiff.Retry)"             # 200
echo "container $(nxcode "$NX_ADMIN" GET /api/v1/path/change-diff)"                              # 200
```

Stop and report if Nuxeo is not running, the administrator cannot log in, `BlobDiff.Retry` is
unknown (404: the package is not installed) or `/change-diff` does not exist. Whether the feature is
**enabled** is checked by the smoke test of § 4.

`new_run` starts a campaign: run it once, after the preflight. To resume an interrupted campaign, do
not run it again: the helpers reload the current run and its saved ids.

```bash
source test-campaign/agent/blobaudit-helpers.sh
new_run
```

## 3. Setup — check, create when missing

### 3.1 Users and group

| User | Groups | Must be |
|---|---|---|
| `alice` | `members` | author; **not** in `auditors` nor `administrators` |
| `bob` | `members` | second author; **not** in `auditors` nor `administrators` |
| `carol` | `members`, `auditors` | the auditor (the configuration names the group `auditors`) |

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=SETUP-users
for u in alice bob carol; do
    if [ "$(nxcode "$NX_ADMIN" GET "/api/v1/user/$u")" = 404 ]; then
        first="$(tr '[:lower:]' '[:upper:]' <<<"${u:0:1}")${u:1}"
        nx "$NX_ADMIN" POST /api/v1/user -H 'Content-Type: application/json' \
            -d "$(jq -cn --arg u "$u" --arg f "$first" --arg p "$TEST_PWD" '{"entity-type": "user", id: $u,
                properties: {username: $u, firstName: $f, lastName: "Tester", email: ($u + "@example.com"),
                password: $p, groups: ["members"]}}')" | jq -r '.id // .message'
        verdict INFO "user $u created"
    fi
    if [ "$(nxcode "$u" GET /api/v1/me)" != 200 ]; then
        # Existing local test user with another password: align it on TEST_PWD.
        nx "$NX_ADMIN" PUT "/api/v1/user/$u" -H 'Content-Type: application/json' \
            -d "$(jq -cn --arg u "$u" --arg p "$TEST_PWD" '{"entity-type": "user", id: $u, properties: {password: $p}}')" >/dev/null
        verdict INFO "password of $u reset to TEST_PWD"
    fi
    check "login $u" 200 "$(nxcode "$u" GET /api/v1/me)"
done
if [ "$(nxcode "$NX_ADMIN" GET /api/v1/group/auditors)" = 404 ]; then
    nx "$NX_ADMIN" POST /api/v1/group -H 'Content-Type: application/json' \
        -d '{"entity-type": "group", "id": "auditors", "properties": {"groupname": "auditors",
             "grouplabel": "Blob diff auditors"}, "memberUsers": ["carol"]}' | jq -r '.id // .message'
    verdict INFO "group auditors created"
fi
groups_of() { nx "$1" GET /api/v1/me | jq -r '[.extendedGroups[]?.name] | join(",")'; }
grep -qw auditors <<<"$(groups_of carol)" || nx "$NX_ADMIN" POST /api/v1/group/auditors/user/carol >/dev/null
check "carol in auditors" yes "$(grep -qw auditors <<<"$(groups_of carol)" && echo yes || echo no)"
for u in alice bob; do
    if grep -qw auditors <<<"$(groups_of "$u")"; then
        nx "$NX_ADMIN" DELETE "/api/v1/group/auditors/user/$u" >/dev/null
        verdict INFO "$u removed from auditors (the test needs a non-auditor)"
    fi
    # Read as the administrator: LTS 2025 writes isAdministrator only for an administrator caller.
    check "$u is not an administrator" false "$(nx "$NX_ADMIN" GET "/api/v1/user/$u" | jq -r .isAdministrator)"
done
```

If `alice` or `bob` is an administrator, stop: the permission checks would be meaningless.

### 3.2 Containers and permissions

| Container | Inheritance | Permissions |
|---|---|---|
| Workspace `$WS` (`blob-audit-tests`, "Blob Audit Tests") | blocked | `alice` ReadWrite, `bob` ReadWrite, `carol` Read |
| Folder `$RESTRICTED` (`restricted`, "Restricted") | blocked | `alice` ReadWrite only |

The default domain grants `Read` to `members`, hence the blocked inheritance. `ReadWrite` includes
`WriteVersion` and `Remove`. carol needs nothing else: the plugin manages the ACL of `/change-diff`.

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=SETUP-containers
has_ace() {  # PATH USER PERMISSION
    nx "$NX_ADMIN" GET "/api/v1/path$1/@acl" | jq --arg u "$2" --arg p "$3" \
        '[.acl[]? | select(.name == "local") | .ace[]? | select(.username == $u and .permission == $p and .granted)] | length > 0'
}
grant() {  # PATH USER PERMISSION
    [ "$(has_ace "$1" "$2" "$3")" = true ] && return
    check "grant $3 to $2 on $1" 200 "$(opcode "$NX_ADMIN" Document.AddPermission "doc:$1" \
        "$(jq -cn --arg u "$2" --arg p "$3" '{username: $u, permission: $p}')")"
}
block() {  # PATH
    nx "$NX_ADMIN" GET "/api/v1/path$1/@acl" | jq -e '.acl[]? | select(.name == "local") | .ace[]?
        | select(.username == "Everyone" and .permission == "Everything" and (.granted | not))' >/dev/null && return
    check "block inheritance on $1" 200 "$(opcode "$NX_ADMIN" Document.BlockPermissionInheritance "doc:$1" '{}')"
}
if [ "$(nxcode "$NX_ADMIN" GET "/api/v1/path$WS")" = 404 ]; then
    create_doc "$NX_ADMIN" "$(dirname "$WS")" "$(basename "$WS")" "Blob Audit Tests" "" Workspace
fi
if [ "$(nxcode "$NX_ADMIN" GET "/api/v1/path$RESTRICTED")" = 404 ]; then
    create_doc "$NX_ADMIN" "$WS" "$(basename "$RESTRICTED")" "Restricted" "" Folder
fi
block "$WS"; grant "$WS" alice ReadWrite; grant "$WS" bob ReadWrite; grant "$WS" carol Read
block "$RESTRICTED"; grant "$RESTRICTED" alice ReadWrite
check "alice reads the workspace" 200 "$(nxcode alice GET "/api/v1/path$WS")"
check "carol reads the workspace" 200 "$(nxcode carol GET "/api/v1/path$WS")"
check "alice reads Restricted" 200 "$(nxcode alice GET "/api/v1/path$RESTRICTED")"
denied "carol does NOT read Restricted" "$(nxcode carol GET "/api/v1/path$RESTRICTED")"
denied "bob does NOT read Restricted" "$(nxcode bob GET "/api/v1/path$RESTRICTED")"
check "carol (auditor) reads /change-diff" 200 "$(nxcode carol GET /api/v1/path/change-diff)"
denied "alice does NOT read /change-diff" "$(nxcode alice GET /api/v1/path/change-diff)"
```

If carol cannot read `/change-diff`, the auditors group in effect is not `auditors`: **stop** and report,
with the group the server actually uses:

```bash
source test-campaign/agent/blobaudit-helpers.sh
nx "$NX_ADMIN" GET /ui/config.jsp | grep -o '"blobaudit":{[^}]*}'   # expected "auditorsGroup":"auditors"
```

- nothing printed, or `administrators`, with the plugin from item 105 on: the configuration file is not
  in `nxserver/config`, or Nuxeo was not restarted after it was added;
- `administrators` with a plugin older than item 105: the plugin's own default overrides the
  configuration unless it also requires `org.nuxeo.audit.advanced.blob.properties` (a component that
  item 105 removed: never add that `<require>` with a recent plugin, it would leave the configuration
  pending, the feature off).

The plugin moves the ACL of `/change-diff` to the configured group at startup (one `WARN`, "Repairing
ACL of diff container").

## 4. Smoke test — S07, plain text

Run it first: if `S07-01` finds no audit entry, the feature is not enabled (the configuration file is
not in `nxserver/config`, or `enabled="true"` is missing). **Stop** and report.

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S07-text-notes
save S07 "$(create_doc alice "$WS" "s07-$RUN" "S07 Notes $RUN" $D/notes-00.txt)"
STEP=S07-00; check version 0.1 "$(version alice "$S07")"
STEP=S07-01; B=$(audit_count "$S07"); replace alice "$S07" $D/notes-01.txt; version alice "$S07"
expect "$S07" "$B" ok 1 0 2
body_has "+ Attendees: product, engineering, support, sales"
body_has "+ Action: engineering prepares the staging and demo environments"
body_has "+ Action: sales lists the pilot customers"
entry_has xpath file:content
check "audit comment" "file:content : binary content modified between versions" "$(jq -r .comment <<<"$LAST_ENTRY")"
```

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S07-text-notes
# CRLF, indentation, trailing spaces, blank lines only: no textual change, but the bytes changed.
STEP=S07-02; B=$(audit_count "$S07"); replace alice "$S07" $D/notes-02.txt; version alice "$S07"
expect "$S07" "$B" ok 0 0 0
prop_is bdiff:summary "No textual change detected"
check "no diff body" "" "$LAST_BODY"
# BOM added and one line removed: the BOM must not show as a change of the first line.
STEP=S07-03; B=$(audit_count "$S07"); replace alice "$S07" $D/notes-03.txt; version alice "$S07"
expect "$S07" "$B" ok 0 1 0
body_has "- Action: support writes the FAQ"
body_lacks "Project kickoff"
STEP=S07-end; total_is "$S07" 3
```

## 5. Scenarios

Run them in order. Each block is independent once `S07` passed; the document ids are kept by `save`.

### S01 — Word, paragraph by paragraph

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S01-word-contract
save S01 "$(create_doc alice "$WS" "s01-$RUN" "S01 Contract $RUN" $D/contract-00.docx)"
STEP=S01-00; check version 0.1 "$(version alice "$S01")"
STEP=S01-01; B=$(audit_count "$S01"); replace alice "$S01" $D/contract-01.docx; version alice "$S01"
expect "$S01" "$B" ok 1 0 1
body_has "- Invoices are payable within 30 days of receipt."
body_has "+ Invoices are payable within 45 days of receipt."
body_has "+ The notice must be sent by registered letter."
STEP=S01-02; B=$(audit_count "$S01"); replace alice "$S01" $D/contract-02.docx; version alice "$S01"
expect "$S01" "$B" ok 0 2 1
body_has "- 4. Liability"
body_has "- The liability of the provider is limited to the amount paid over the last twelve months."
body_has "+ 2026-02-01"
# Bold and page header only: formatting and headers are not compared.
STEP=S01-03; B=$(audit_count "$S01"); replace alice "$S01" $D/contract-03.docx; version alice "$S01"
expect "$S01" "$B" ok 0 0 0
prop_is bdiff:summary "No textual change detected"
STEP=S01-end; total_is "$S01" 3
```

### S02 — Word with images (image inventory)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S02-word-images
save S02 "$(create_doc alice "$WS" "s02-$RUN" "S02 Product sheet $RUN" $D/word-images-00.docx)"
STEP=S02-00; check version 0.1 "$(version alice "$S02")"
STEP=S02-01; B=$(audit_count "$S02"); replace alice "$S02" $D/word-images-01.docx; version alice "$S02"
expect "$S02" "$B" ok 0 0 1
body_has "# Images"
body_has "~ word:image:image1.png : sha256:"
STEP=S02-02; B=$(audit_count "$S02"); replace alice "$S02" $D/word-images-02.docx; version alice "$S02"
expect "$S02" "$B" ok 2 0 0
body_has "+ Figure 2: detail view."
body_has "+ word:image:image2.jpg = sha256:"
# The first picture removed: the remaining one is renamed image1.jpg, with the digest image2.jpg had.
STEP=S02-03; B=$(audit_count "$S02"); replace alice "$S02" $D/word-images-03.docx; version alice "$S02"
expect "$S02" "$B" ok 1 2 0
body_has "- word:image:image1.png"
body_has "- word:image:image2.jpg"
body_has "+ word:image:image1.jpg"
check "image1.jpg has the digest of image2.jpg" 1 \
    "$(grep -E 'image2.jpg|image1.jpg' <<<"$LAST_BODY" | grep -o 'sha256:[0-9a-f]*' | sort -u | wc -l | tr -d ' ')"
STEP=S02-end; total_is "$S02" 3
```

### S03 — Excel, cell by cell

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S03-excel-budget
save S03 "$(create_doc alice "$WS" "s03-$RUN" "S03 Budget $RUN" $D/budget-00.xlsx)"
STEP=S03-00; check version 0.1 "$(version alice "$S03")"
STEP=S03-01; B=$(audit_count "$S03"); replace alice "$S03" $D/budget-01.xlsx; version alice "$S03"
expect "$S03" "$B" ok 0 0 3
body_has "~ Budget!C3 : 1200 -> 1500"
body_has "~ Budget!E5 : 300 -> 350"
# A multi-line cell stays on one line, its line break written as a literal \n.
body_has '~ Notes!B2 : Figures approved\nby the steering committee -> Figures approved\nby the board'
STEP=S03-02; B=$(audit_count "$S03"); replace alice "$S03" $D/budget-02.xlsx; version alice "$S03"
expect "$S03" "$B" ok 6 0 5
body_has "+ Budget!A7 = Travel"
body_has "+ Budget!F7 = =SUM(B7:E7)"
body_has "~ Budget!B8 : =SUM(B2:B6) -> =SUM(B2:B7)"
body_has "~ Budget!F8 : =SUM(F2:F6) -> =SUM(F2:F7)"
# Sheet renamed: every cell of the sheet removed and added (documented limitation).
STEP=S03-03; B=$(audit_count "$S03"); replace alice "$S03" $D/budget-03.xlsx; version alice "$S03"
expect "$S03" "$B" ok 6 6 0
body_has "+ Remarks!A1 = Owner"
body_has "- Notes!A1 = Owner"
STEP=S03-end; total_is "$S03" 3
```

### S04 — Excel with images

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S04-excel-images
save S04 "$(create_doc alice "$WS" "s04-$RUN" "S04 Pictures $RUN" $D/excel-images-00.xlsx)"
STEP=S04-00; check version 0.1 "$(version alice "$S04")"
# Same picture moved from B2 to E10: the key holds the cell.
STEP=S04-01; B=$(audit_count "$S04"); replace alice "$S04" $D/excel-images-01.xlsx; version alice "$S04"
expect "$S04" "$B" ok 1 1 0
body_has "- excel:image:Pictures:B2 = sha256:"
body_has "+ excel:image:Pictures:E10 = sha256:"
check "same picture, same digest" 1 "$(grep -o 'sha256:[0-9a-f]*' <<<"$LAST_BODY" | sort -u | wc -l | tr -d ' ')"
STEP=S04-02; B=$(audit_count "$S04"); replace alice "$S04" $D/excel-images-02.xlsx; version alice "$S04"
expect "$S04" "$B" ok 0 0 1
body_has "~ excel:image:Pictures:E10 : sha256:"
STEP=S04-03; B=$(audit_count "$S04"); replace alice "$S04" $D/excel-images-03.xlsx; version alice "$S04"
expect "$S04" "$B" ok 1 0 1
body_has "~ Pictures!A1 : Product photo -> Product photos"
body_has "+ excel:image:Pictures:H2 = sha256:"
STEP=S04-end; total_is "$S04" 3
```

### S05 — PowerPoint, slide by slide

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S05-powerpoint-deck
save S05 "$(create_doc alice "$WS" "s05-$RUN" "S05 Deck $RUN" $D/deck-00.pptx)"
STEP=S05-00; check version 0.1 "$(version alice "$S05")"
STEP=S05-01; B=$(audit_count "$S05"); replace alice "$S05" $D/deck-01.pptx; version alice "$S05"
expect "$S05" "$B" ok 0 0 3
body_has "+ Revenue up 14 percent"
body_has "+ [notes] Mention the new customers in Lyon and Nantes."
body_has "+ [table] South | 95 | 118"
# A bullet removed and a slide added. The removal is immediately followed by the heading of the new
# slide: the two are coalesced into one modification (documented, plugin README "Limitations").
STEP=S05-02; B=$(audit_count "$S05"); replace alice "$S05" $D/deck-02.pptx; version alice "$S05"
expect "$S05" "$B" ok 3 0 1
body_has "- Churn stable at 3 percent"
body_has "+ ## Slide: Risks"
body_has "+ Currency exposure"
body_has "+ Supplier delays"
# A soft line break inside a paragraph is written as a literal \n; a picture is added on slide 3.
STEP=S05-03; B=$(audit_count "$S05"); replace alice "$S05" $D/deck-03.pptx; version alice "$S05"
expect "$S05" "$B" ok 1 0 1
body_has '+ Open the Madrid office\nin February'
body_has "# Images"
body_has "+ powerpoint:slide:3:image:"
STEP=S05-end; total_is "$S05" 3
```

### S06 — PDF, line by line as rendered

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S06-pdf-report
save S06 "$(create_doc alice "$WS" "s06-$RUN" "S06 Report $RUN" $D/report-00.pdf)"
STEP=S06-00; check version 0.1 "$(version alice "$S06")"
STEP=S06-01; B=$(audit_count "$S06"); replace alice "$S06" $D/report-01.pdf; version alice "$S06"
expect "$S06" "$B" ok 1 0 1
body_has "+ Incidents closed: 13"
body_has "+ Repaint the emergency exits."
body_lacks "# Images"
STEP=S06-02; B=$(audit_count "$S06"); replace alice "$S06" $D/report-02.pdf; version alice "$S06"
expect "$S06" "$B" ok 0 0 1
body_has "~ pdf:page:1:image:1 : sha256:"
# A page inserted in position 2: the image of the old page 2 is now keyed on page 3.
STEP=S06-03; B=$(audit_count "$S06"); replace alice "$S06" $D/report-03.pdf; version alice "$S06"
expect "$S06" "$B" ok 4 1 0
body_has "+ Section 1b - Budget"
body_has "+ Budget allocated: 25000 EUR"
body_has "+ Budget spent: 21300 EUR"
body_has "- pdf:page:2:image:1 = sha256:"
body_has "+ pdf:page:3:image:1 = sha256:"
STEP=S06-end; total_is "$S06" 3
```

### S08 — JSON replaced by its XML equivalent

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S08-json-replaced-by-xml
save S08 "$(create_doc alice "$WS" "s08-$RUN" "S08 Config $RUN" $D/config-00.json)"
STEP=S08-00; check version 0.1 "$(version alice "$S08")"
STEP=S08-01; B=$(audit_count "$S08"); replace alice "$S08" $D/config-01.json; version alice "$S08"
expect "$S08" "$B" ok 0 0 2
body_has '+ "pool": 20,'
body_has '+ "logLevel": "DEBUG"'
# Mime type changed and almost nothing shared: filed without its body, decided by the work.
STEP=S08-02; B=$(audit_count "$S08"); replace alice "$S08" $D/config-02.xml; version alice "$S08"
expect "$S08" "$B" status skippedContentReplaced
similarity_in 0 0.1
check "no diff body" "" "$LAST_BODY"
entry_has oldMimeType application/json
entry_has newMimeType text/xml
STEP=S08-03; B=$(audit_count "$S08"); replace alice "$S08" $D/config-03.xml; version alice "$S08"
expect "$S08" "$B" ok 0 0 1
body_has "+ <port>9090</port>"
STEP=S08-end; total_is "$S08" 3
```

### S09 — a .txt saved as .csv

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S09-txt-to-csv
save S09 "$(create_doc alice "$WS" "s09-$RUN" "S09 Data $RUN" $D/data-00.txt)"
STEP=S09-00; check version 0.1 "$(version alice "$S09")"
STEP=S09-01; B=$(audit_count "$S09"); replace alice "$S09" $D/data-01.csv; version alice "$S09"
expect "$S09" "$B" ok 0 0 1
body_has "+ 4,Delta SA,Madrid,650"
similarity_in 0.8 0.95
prop_is bdiff:oldMimeType text/plain
prop_is bdiff:newMimeType text/csv
```

### S10 — Markdown and YAML (two documents)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S10-markdown-and-yaml
save S10A "$(create_doc alice "$WS" "s10a-$RUN" "S10 Readme $RUN" $D/readme-00.md)"
STEP=S10A-00; check version 0.1 "$(version alice "$S10A")"
STEP=S10A-01; B=$(audit_count "$S10A"); replace alice "$S10A" $D/readme-01.md; version alice "$S10A"
expect "$S10A" "$B" ok 1 0 1
body_has "+ ## Version 3.3"
body_has "+ - Dark mode"
save S10B "$(create_doc alice "$WS" "s10b-$RUN" "S10 Pipeline $RUN" $D/pipeline-00.yaml)"
STEP=S10B-00; check version 0.1 "$(version alice "$S10B")"
STEP=S10B-01; B=$(audit_count "$S10B"); replace alice "$S10B" $D/pipeline-01.yaml; version alice "$S10B"
expect "$S10B" "$B" ok 1 0 1
body_has "+ environment: production"
body_has "+ - security-scan"
```

### S11 — the Word family (`converter` contribution), RTF under both of its mime types

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S11-word-family
save S11 "$(create_doc alice "$WS" "s11-$RUN" "S11 Letter $RUN" $D/letter-00.docx)"
STEP=S11-00; check version 0.1 "$(version alice "$S11")"
STEP=S11-01; B=$(audit_count "$S11"); replace alice "$S11" $D/letter-01.odt; version alice "$S11"
expect "$S11" "$B" ok 0 0 1
body_has "+ We confirm that the goods will be delivered on November 22."
STEP=S11-02; B=$(audit_count "$S11"); replace alice "$S11" $D/letter-02.doc; version alice "$S11"
expect "$S11" "$B" ok 1 0 0
body_has "+ A tracking number will follow."
# .rtf sent as text/rtf: the type the Nuxeo mimetype registry normalizes .rtf to.
STEP=S11-03; B=$(audit_count "$S11"); replace alice "$S11" $D/letter-03.rtf text/rtf; version alice "$S11"
expect "$S11" "$B" ok 0 0 1
body_has "+ John Roe, Customer Service"
```

The `converter` contribution lists `text/rtf` and `application/rtf` (plugin item 106) and reads `.doc`,
`.docx`, `.odt` and `.rtf` alike. A `skip skippedFormatChanged` with `application/msword -> text/rtf`
means a plugin older than item 106, where `plainText` and its `text/*` took `text/rtf`.

**P1 — the same RTF sent as `application/rtf`:**

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S11-word-family
save P1 "$(create_doc alice "$WS" "p1-$RUN" "P1 Letter RTF $RUN" $D/letter-02.doc)"
STEP=P1-00; check version 0.1 "$(version alice "$P1")"
STEP=P1-01; B=$(audit_count "$P1"); replace alice "$P1" $D/letter-03.rtf application/rtf; version alice "$P1"
expect "$P1" "$B" ok 0 0 1
body_has "+ John Roe, Customer Service"
```

### S12 — .xls replaced by .xlsx

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S12-xls-to-xlsx
save S12 "$(create_doc alice "$WS" "s12-$RUN" "S12 Sales $RUN" $D/legacy-sales-00.xls)"
STEP=S12-00; check version 0.1 "$(version alice "$S12")"
STEP=S12-01; B=$(audit_count "$S12"); replace alice "$S12" $D/legacy-sales-01.xlsx; version alice "$S12"
expect "$S12" "$B" ok 0 0 1
body_has "~ Sales!B3 : 95 -> 110"
```

### S13 — format changes

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S13-format-change
save S13 "$(create_doc alice "$WS" "s13-$RUN" "S13 Memo $RUN" $D/format-change-00.docx)"
STEP=S13-00; check version 0.1 "$(version alice "$S13")"
STEP=S13-01; B=$(audit_count "$S13"); replace alice "$S13" $D/format-change-01.pdf; version alice "$S13"
expect "$S13" "$B" skip skippedFormatChanged
check "audit comment" "file:content : binary changed (format changed)" "$(jq -r .comment <<<"$LAST_ENTRY")"
STEP=S13-02; B=$(audit_count "$S13"); replace alice "$S13" $D/format-change-02.xlsx; version alice "$S13"
expect "$S13" "$B" skip skippedFormatChanged
STEP=S13-03; B=$(audit_count "$S13"); replace alice "$S13" $D/format-change-03.xlsx; version alice "$S13"
expect "$S13" "$B" ok 0 0 1
body_has "~ Memo!A3 : The team moves to the third floor on December 1. -> The team moves to the third floor on December 8."
STEP=S13-end; check "BlobDiffs of S13 (the skips create none)" 1 "$(diffs "$S13" | jq length)"
```

### S14 — legacy PowerPoint

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S14-legacy-ppt
save S14 "$(create_doc alice "$WS" "s14-$RUN" "S14 Training $RUN" $D/legacy-deck-00.ppt)"
STEP=S14-00; check version 0.1 "$(version alice "$S14")"
# Flat text through any2text: the exact counters depend on the converter, the changed line does not.
STEP=S14-01; B=$(audit_count "$S14"); replace alice "$S14" $D/legacy-deck-01.ppt; version alice "$S14"
expect "$S14" "$B" status ok
body_has "Week 2: customer cases and demos"
STEP=S14-02; B=$(audit_count "$S14"); replace alice "$S14" $D/legacy-deck-02.pptx; version alice "$S14"
expect "$S14" "$B" skip skippedFormatChanged
```

### S15 — an Excel cell at the maximum size (32 767 characters)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S15-excel-big-cell
save S15 "$(create_doc alice "$WS" "s15-$RUN" "S15 Big cell $RUN" $D/big-cell-00.xlsx)"
STEP=S15-00; check version 0.1 "$(version alice "$S15")"
STEP=S15-01; B=$(audit_count "$S15"); replace alice "$S15" $D/big-cell-01.xlsx; version alice "$S15"
expect "$S15" "$B" ok 0 0 1
body_has "~ Big!B1 : This cell holds a very long legal clause"
body_has "[32767 chars, sha256:"
check "the two compacted values have different digests" 2 \
    "$(grep -o '32767 chars, sha256:[0-9a-f]*' <<<"$LAST_BODY" | sort -u | wc -l | tr -d ' ')"
check "longest body line stays under 8 300 characters" yes \
    "$(awk '{ if (length($0) > m) m = length($0) } END { print (m < 8300) ? "yes" : "no (" m ")" }' <<<"$LAST_BODY")"
```

### S16 — over `maxBlobSize` (10 MB)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S16-too-large
save S16 "$(create_doc alice "$WS" "s16-$RUN" "S16 Sensor log $RUN" $D/too-large-00.txt)"
STEP=S16-00; check version 0.1 "$(version alice "$S16")"
STEP=S16-01; B=$(audit_count "$S16"); replace alice "$S16" $D/too-large-01.txt; version alice "$S16"
expect "$S16" "$B" skip skippedTooLarge
check "audit comment" "file:content : binary changed (too large)" "$(jq -r .comment <<<"$LAST_ENTRY")"
# 6 KB now, but the previous version is still over the limit.
STEP=S16-02; B=$(audit_count "$S16"); replace alice "$S16" $D/too-large-02.txt; version alice "$S16"
expect "$S16" "$B" skip skippedTooLarge
STEP=S16-03; B=$(audit_count "$S16"); replace alice "$S16" $D/too-large-03.txt; version alice "$S16"
expect "$S16" "$B" ok 0 0 1
body_has "+ Record 0000010: sensor reading 010 at station 10, status ALERT"
STEP=S16-end; check "BlobDiffs of S16" 1 "$(diffs "$S16" | jq length)"
```

### S17 — unsupported format (PNG in a File)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S17-unsupported-format
save S17 "$(create_doc alice "$WS" "s17-$RUN" "S17 Packshot $RUN" $D/picture-00.png)"
STEP=S17-00; check version 0.1 "$(version alice "$S17")"
STEP=S17-01; B=$(audit_count "$S17"); replace alice "$S17" $D/picture-01.png; version alice "$S17"
expect "$S17" "$B" skip skippedUnsupportedType
# PNG -> text: an unsupported side wins over a format change.
STEP=S17-02; B=$(audit_count "$S17"); replace alice "$S17" $D/picture-02.txt; version alice "$S17"
expect "$S17" "$B" skip skippedUnsupportedType
STEP=S17-03; B=$(audit_count "$S17"); replace alice "$S17" $D/picture-03.txt; version alice "$S17"
expect "$S17" "$B" ok 0 0 1
body_has "+ Usage: catalog and website"
```

### S18 — silent cases: nothing may be audited

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S18-same-bytes-renamed
save S18 "$(create_doc alice "$WS" "s18-$RUN" "S18 Checklist $RUN" $D/same-bytes-00.txt)"
STEP=S18-00; check version 0.1 "$(version alice "$S18")"
# Identical bytes under another name and extension (and another mime type).
STEP=S18-01; B=$(audit_count "$S18"); replace alice "$S18" $D/same-bytes-01.md; check version 0.2 "$(version alice "$S18")"
expect "$S18" "$B" none
STEP=S18-02; B=$(audit_count "$S18"); replace alice "$S18" $D/same-bytes-02.md; version alice "$S18"
expect "$S18" "$B" ok 1 0 0
body_has "+ - sign the artifacts"
# A metadata change only.
STEP=S18-03; B=$(audit_count "$S18"); set_prop alice "$S18" dc:title "S18 Checklist renamed $RUN"; version alice "$S18"
expect "$S18" "$B" none
# A File holding no file at all.
save S18N "$(create_doc alice "$WS" "s18n-$RUN" "S18 No file $RUN")"
STEP=S18-04; version alice "$S18N"; set_prop alice "$S18N" dc:description "changed"; version alice "$S18N"
expect "$S18N" 0 none
```

### S19 — a corrupted file: error, retry, error again, recovery

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S19-broken-file
save S19 "$(create_doc alice "$WS" "s19-$RUN" "S19 Stock $RUN" $D/broken-00.xlsx)"
STEP=S19-00; check version 0.1 "$(version alice "$S19")"
STEP=S19-01; B=$(audit_count "$S19"); replace alice "$S19" $D/broken-01.xlsx; version alice "$S19"
expect "$S19" "$B" status error
check "no diff body" "" "$LAST_BODY"
prop_is bdiff:newFilename broken-01.xlsx
check "both digests kept" true "$(jq '.properties["bdiff:oldDigest"] != null and .properties["bdiff:newDigest"] != null' <<<"$LAST_DIFF")"
save S19_D1 "$LAST_DIFF_ID"
save S19_CID "$(jq -r '.properties["bdiff:correlationId"]' <<<"$LAST_DIFF")"
```

Retry, as the auditor:

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=S19-retry
check "retry accepted" 200 "$(opcode carol BlobDiff.Retry "doc:$S19_D1" '{}')"
code=$(opcode carol BlobDiff.Retry "doc:$S19_D1" '{}')
case $code in
409) verdict PASS "second retry refused while the first is pending (409)" ;;
200) verdict INFO "second retry accepted (200): the first replay had already finished" ;;
*) verdict FAIL "second retry answered $code, expected 409 (or 200 if the first replay had finished)" ;;
esac
NEW=$(wait_diff "$S19_CID" "$S19" "$S19_D1") && {
    check "replay filed in error again (file still corrupted)" error "$(jq -r '.properties["bdiff:status"]' <<<"$NEW")"
    save S19_D2 "$(jq -r .uid <<<"$NEW")"
} || verdict FAIL "no new BlobDiff after the retry"
sleep 5
denied "the replaced error diff is gone" "$(nxcode carol GET "/api/v1/id/$S19_D1")"
check "BlobDiffs of S19 with that correlation id" 1 \
    "$(diffs "$S19" | jq --arg c "$S19_CID" '[.[] | select(.properties["bdiff:correlationId"] == $c)] | length')"
```

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S19-broken-file
# The previous version is the corrupted one.
STEP=S19-02; B=$(audit_count "$S19"); replace alice "$S19" $D/broken-02.xlsx; version alice "$S19"
expect "$S19" "$B" status error
STEP=S19-03; B=$(audit_count "$S19"); replace alice "$S19" $D/broken-03.xlsx; version alice "$S19"
expect "$S19" "$B" ok 0 0 1
body_has "~ Stock!B2 : 14 -> 16"
STEP=S19-retry-ok; code=$(opcode carol BlobDiff.Retry "doc:$LAST_DIFF_ID" '{}')
check "retry refused on a diff that is not in error" 400 "$code"
```

### S20 — over `maxLines` (12 000 lines)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S20-truncated-lines
save S20 "$(create_doc alice "$WS" "s20-$RUN" "S20 Log $RUN" $D/long-log-00.txt)"
STEP=S20-00; check version 0.1 "$(version alice "$S20")"
STEP=S20-01; B=$(audit_count "$S20"); replace alice "$S20" $D/long-log-01.txt; version alice "$S20"
expect "$S20" "$B" ok 0 0 1
prop_is bdiff:truncated true
body_has "+ 2026-11-01 10:01:40 WARN  [worker-1] event 00100 processed"
# Line 11 000 changed too, but past the 10 000 lines read: neither reported nor counted.
body_lacks "event 11000"
```

### S21 — over `maxDiffEntries` (6 000 cells changed)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S21-excel-mass-update
save S21 "$(create_doc alice "$WS" "s21-$RUN" "S21 Mass update $RUN" $D/mass-update-00.xlsx)"
STEP=S21-00; check version 0.1 "$(version alice "$S21")"
STEP=S21-01; B=$(audit_count "$S21"); replace alice "$S21" $D/mass-update-01.xlsx; version alice "$S21"
expect "$S21" "$B" ok 0 0 6000
prop_is bdiff:truncated true
check "body entries capped at maxDiffEntries" 5000 "$(grep -c '^~ Data!A' <<<"$LAST_BODY")"
body_has "~ Data!A1 : 10 -> 11"
```

### S22 — a text rewritten from end to end, same format

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S22-rewrite-same-format
save S22 "$(create_doc alice "$WS" "s22-$RUN" "S22 Notice $RUN" $D/rewrite-00.txt)"
STEP=S22-00; check version 0.1 "$(version alice "$S22")"
# Same mime type: always diffed, never "content replaced", whatever the similarity.
STEP=S22-01; B=$(audit_count "$S22"); replace alice "$S22" $D/rewrite-01.txt; version alice "$S22"
expect "$S22" "$B" status ok
similarity_in 0 0.1
body_has "+ Parking rules"
body_has "- Cafeteria menu - week 45"
verdict INFO "counters (coalescing, not an exact edit script): $(jq -r '.properties | "\(.["bdiff:added"])/\(.["bdiff:removed"])/\(.["bdiff:changed"])"' <<<"$LAST_DIFF")"
save S22_D "$LAST_DIFF_ID"
```

### S23 — attachments (`files:files`, needs `<xpath>*</xpath>`)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S23-attachments
save S23 "$(create_doc alice "$WS" "s23-$RUN" "S23 Attachments $RUN" $D/main-00.txt)"
attach_at alice "$S23" $D/attach-alpha-00.txt files:files
attach_at alice "$S23" $D/attach-beta-00.txt files:files
STEP=S23-00; check version 0.1 "$(version alice "$S23")"
check "attachments" "attach-alpha-00.txt,attach-beta-00.txt" \
    "$(nx alice GET "/api/v1/id/$S23" -H 'properties: files' | jq -r '[.properties["files:files"][].file.name] | join(",")')"
# Attachment 0 replaced in place: the list kept its shape, the old file is gone.
STEP=S23-01; B=$(audit_count "$S23"); attach_at alice "$S23" $D/attach-alpha-01.txt files:files/0/file; version alice "$S23"
expect "$S23" "$B" ok 0 0 1
prop_is bdiff:xpath files:files/0/file
body_has "+ Amount: 12500 EUR"
# An added attachment is not audited.
STEP=S23-02; B=$(audit_count "$S23"); attach_at alice "$S23" $D/attach-gamma-00.txt files:files; version alice "$S23"
expect "$S23" "$B" none
# beta removed AND alpha replaced: the file alpha-02 replaces cannot be told.
STEP=S23-03; B=$(audit_count "$S23")
remove_at alice "$S23" files:files/1
attach_at alice "$S23" $D/attach-alpha-02.txt files:files/0/file
check "attachments before the version" "attach-alpha-02.txt,attach-gamma-00.txt" \
    "$(nx alice GET "/api/v1/id/$S23" -H 'properties: files' | jq -r '[.properties["files:files"][].file.name] | join(",")')"
version alice "$S23"
expect "$S23" "$B" skip skippedListChanged
entry_has xpath files:files/0/file
STEP=S23-end; total_is "$S23" 2
```

### S24 — two authors, automatic version (`collaborative-save`)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S24-collaborative-save
save S24 "$(create_doc alice "$WS" "s24-$RUN" "S24 Procedure $RUN" $D/collab-00.txt)"
STEP=S24-00; check version 0.1 "$(version alice "$S24")"
# alice saves a new file without versioning: a save is never audited.
STEP=S24-01; B=$(audit_count "$S24"); replace alice "$S24" $D/collab-01.txt
expect "$S24" "$B" none
# bob saves over alice's work: the platform policy versions alice's state first (0.2), in bob's save.
STEP=S24-02; B=$(audit_count "$S24"); replace bob "$S24" $D/collab-02.txt
check "label after bob's save" 0.2+ "$(label bob "$S24")"
expect "$S24" "$B" ok 0 0 1
body_has "+ Step 2: assign an owner within one day"
prop_is bdiff:user bob
STEP=S24-03; B=$(audit_count "$S24"); check version 0.3 "$(version bob "$S24")"
expect "$S24" "$B" ok 0 0 1
body_has "+ Step 3: close the ticket and notify"
prop_is bdiff:user bob
```

If `S24-02` finds no automatic version (label `0.1+`), the `collaborative-save` policy is disabled on
this instance: log `INFO`, not `FAIL`.

### S25 — a document type out of scope (Picture)

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S25-out-of-scope-type
save S25 "$(create_doc alice "$WS" "s25-$RUN" "S25 Picture $RUN" $D/scope-00.png Picture)"
STEP=S25-00; check version 0.1 "$(version alice "$S25")"
STEP=S25-01; replace alice "$S25" $D/scope-01.png; check version 0.2 "$(version alice "$S25")"
expect "$S25" 0 none
check "BlobDiffs of S25" 0 "$(diffs "$S25" | jq length)"
```

### S26 — a source the auditor cannot read

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S26-restricted-source
save S26 "$(create_doc alice "$RESTRICTED" "s26-$RUN" "S26 Board minutes $RUN" $D/secret-00.txt)"
STEP=S26-00; check version 0.1 "$(version alice "$S26")"
STEP=S26-01; B=$(audit_count "$S26"); replace alice "$S26" $D/secret-01.txt; version alice "$S26"
expect "$S26" "$B" ok 0 0 1
body_has "+ Item 1: acquisition of a competitor postponed"
denied "carol cannot open the source" "$(nxcode carol GET "/api/v1/id/$S26")"
S=$(source_state "$LAST_DIFF_ID")
check "enricher: exists" true "$(jq -r .exists <<<"$S")"
check "enricher: readable" false "$(jq -r .readable <<<"$S")"
check "enricher: no title leaked" null "$(jq -r .title <<<"$S")"
```

### S27 — source trashed, then permanently deleted

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S27-source-lifecycle
save S27 "$(create_doc alice "$WS" "s27-$RUN" "S27 Policy $RUN" $D/lifecycle-00.txt)"
STEP=S27-00; check version 0.1 "$(version alice "$S27")"
STEP=S27-01; B=$(audit_count "$S27"); replace alice "$S27" $D/lifecycle-01.txt; version alice "$S27"
expect "$S27" "$B" ok 0 0 1
save S27_D "$LAST_DIFF_ID"
check "enricher before: readable" true "$(source_state "$S27_D" | jq -r .readable)"
STEP=S27-trash; check "trash" 200 "$(opcode alice Document.Trash "doc:$S27" '{}')"
check "enricher: trashed" true "$(source_state "$S27_D" | jq -r .trashed)"
STEP=S27-delete; check "permanent delete" 204 "$(nxcode alice DELETE "/api/v1/id/$S27")"
check "enricher: exists" false "$(source_state "$S27_D" | jq -r .exists)"
check "the diff outlives its source" 200 "$(nxcode carol GET "/api/v1/id/$S27_D")"
```

### S28 — same text, UTF-8 then ISO-8859-1

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S28-charset
save S28 "$(create_doc alice "$WS" "s28-$RUN" "S28 Minutes $RUN" $D/charset-00.txt)"
STEP=S28-00; check version 0.1 "$(version alice "$S28")"
STEP=S28-01; B=$(audit_count "$S28"); replace alice "$S28" $D/charset-01.txt; version alice "$S28"
expect "$S28" "$B" ok 0 0 2
body_has "+ Le comit"
body_has "$(printf '\xef\xbf\xbd')"
body_lacks "Aucun changement ici"
```

No charset detection: the ISO-8859-1 bytes are read as UTF-8 and the accented characters come back
as U+FFFD. Documented behaviour, log it `PASS` when observed.

### P2 — YAML sent as `application/yaml`

```bash
source test-campaign/agent/blobaudit-helpers.sh
D=S10-markdown-and-yaml
save P2 "$(create_doc alice "$WS" "p2-$RUN" "P2 Pipeline $RUN" $D/pipeline-00.yaml File application/x-yaml)"
STEP=P2-00; check version 0.1 "$(version alice "$P2")"
# The old version typed application/x-yaml, the new one application/yaml: one contribution, a diff.
STEP=P2-01; B=$(audit_count "$P2"); replace alice "$P2" $D/pipeline-01.yaml application/yaml; version alice "$P2"
expect "$P2" "$B" ok 1 0 1
body_has "+ environment: production"
body_has "+ - security-scan"
```

`application/yaml` is the type RFC 9512 registered; `plainText` lists it beside `application/x-yaml`
since plugin item 107. A `skip skippedUnsupportedType` means an older plugin: a browser or client
sending the registered type loses the diff of every YAML file.

## 6. Security and auditor operations

Run after § 5 (they use the diffs of S01, S08, S19, S22, S27).

### A1 — a non-auditor reaches no diff and no operation

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=A1
for u in alice bob; do
    denied "$u reads a BlobDiff" "$(nxcode "$u" GET "/api/v1/id/$S19_D2")"
    check "$u finds no BlobDiff by NXQL" 0 "$(nx "$u" GET /api/v1/search/lang/NXQL/execute -G \
        --data-urlencode 'query=SELECT * FROM BlobDiff' | jq '.resultsCount // (.entries | length)')"
    check "$u gets nothing from BLOB_DIFFS_ADMIN" 0 \
        "$(nx "$u" GET '/api/v1/search/pp/BLOB_DIFFS_ADMIN/execute?pageSize=5' | jq '.resultsCount // (.entries | length)')"
    denied "$u runs BlobDiff.Purge" "$(opcode "$u" BlobDiff.Purge "" "$(jq -cn --arg b "$(tomorrow_utc)" '{before: $b}')")"
    denied "$u runs BlobDiff.PurgeStatus" "$(opcode "$u" BlobDiff.PurgeStatus "" '{"commandId": "x"}')"
    denied "$u runs BlobDiff.PurgeAbort" "$(opcode "$u" BlobDiff.PurgeAbort "" '{"commandId": "x"}')"
    denied "$u runs BlobDiff.CleanEmptyFolders" "$(opcode "$u" BlobDiff.CleanEmptyFolders "" '{}')"
    denied "$u runs BlobDiff.Retry" "$(opcode "$u" BlobDiff.Retry "doc:$S19_D2" '{}')"
    denied "$u runs BlobDiff.Delete" "$(opcode "$u" BlobDiff.Delete "doc:$S22_D" '{}')"
done
# The purge bulk action is not exposed over HTTP, even to an auditor.
denied "carol runs Bulk.RunAction blobDiffPurge" \
    "$(opcode carol Bulk.RunAction "" '{"action": "blobDiffPurge", "query": "SELECT * FROM BlobDiff"}')"
```

### A2 — the audit entry carries metadata only, and its author can read it

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=A2
E=$(nx alice GET "/api/v1/id/$S01/@audit?eventId=blobContentModified&pageSize=50")
check "alice reads the entries of her document" 3 "$(jq '.entries | length' <<<"$E")"
check "no extracted content in the entries" 0 "$(jq -c . <<<"$E" | grep -c -e 'Invoices' -e 'Liability' -e 'registered letter')"
verdict INFO "extended keys: $(jq -c '[.entries[].extended | keys[]] | unique' <<<"$E")"
check "category" eventDocumentCategory "$(jq -r '.entries[0].category' <<<"$E")"
check "who" alice "$(jq -r '.entries[0].principalName' <<<"$E")"
```

The extended keys must be among `xpath`, `oldFilename`, `newFilename`, `oldMimeType`, `newMimeType`,
`diffCorrelationId`, `skipReason`: log a `FAIL` for any other key holding document content.

### A3 — the audit search and its facets (OpenSearch)

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=A3
n=0
for i in $(seq 1 20); do
    R=$(nx carol GET '/api/v1/search/pp/BLOB_DIFFS_ADMIN/execute?pageSize=5')
    n=$(jq '[.aggregations.bdiff_status_agg.buckets[]?] | length' <<<"$R")
    [ "$n" -gt 0 ] && break
    sleep 3
done
check "status facet filled (search client is OpenSearch)" yes "$([ "$n" -gt 0 ] && echo yes || echo no)"
for agg in bdiff_status_agg bdiff_mimetype_agg bdiff_xpath_agg bdiff_user_agg; do
    verdict INFO "$agg: $(jq -c --arg a "$agg" '[.aggregations[$a].buckets[]? | "\(.key)=\(.docCount)"]' <<<"$R")"
done
for k in ok error skippedContentReplaced; do
    check "status bucket $k" yes "$(jq -r --arg k "$k" '[.aggregations.bdiff_status_agg.buckets[]?.key] | index($k) != null | if . then "yes" else "no" end' <<<"$R")"
done
check "field bucket files:files/0/file" yes \
    "$(jq -r '[.aggregations.bdiff_xpath_agg.buckets[]?.key] | index("files:files/0/file") != null | if . then "yes" else "no" end' <<<"$R")"
check "user bucket bob" yes \
    "$(jq -r '[.aggregations.bdiff_user_agg.buckets[]?.key] | index("bob") != null | if . then "yes" else "no" end' <<<"$R")"
```

Empty facets with a non-empty result list mean the search client is the default `repository` one,
not OpenSearch: log a `FAIL` with that note (a deployment issue, not a plugin one).

### A4 — permanent deletion

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=A4
code=$(opcode carol BlobDiff.Delete "doc:$S22_D" '{}')
case $code in 200 | 204) verdict PASS "BlobDiff.Delete answered $code" ;; *) verdict FAIL "BlobDiff.Delete answered $code" ;; esac
denied "the deleted diff is gone, not trashed" "$(nxcode "$NX_ADMIN" GET "/api/v1/id/$S22_D")"
check "BlobDiff.Delete refuses another document type" 400 "$(opcode carol BlobDiff.Delete "doc:$S01" '{}')"
# The same diff listed twice, as a results list refreshed before the index caught up can select it.
code=$(opcode carol BlobDiff.Delete "docs:$S27_D,$S27_D" '{}')
case $code in 200 | 204) verdict PASS "a diff listed twice: BlobDiff.Delete answered $code" ;; *) verdict FAIL "a diff listed twice: BlobDiff.Delete answered $code" ;; esac
denied "the diff listed twice is gone" "$(nxcode "$NX_ADMIN" GET "/api/v1/id/$S27_D")"
```

A 500 on the diff listed twice means a plugin older than item 110: the platform removed the document
twice and the whole deletion was rolled back.

### A5 — purge of the error diffs, then empty folders

```bash
source test-campaign/agent/blobaudit-helpers.sh
STEP=A5
T=$(tomorrow_utc)
R=$(op carol BlobDiff.Purge "" "$(jq -cn --arg b "$T" '{before: $b, status: "error"}')")
CMD=$(jq -r .commandId <<<"$R")
check "purge scheduled" SCHEDULED "$(jq -r .state <<<"$R")"
code=$(opcode carol BlobDiff.Purge "" "$(jq -cn --arg b "$T" '{before: $b, status: "error"}')")
case $code in
409) verdict PASS "a second purge is refused while one runs (409)" ;;
200) verdict INFO "a second purge was accepted (200): the first one had already finished" ;;
*) verdict FAIL "a second purge answered $code, expected 409 (or 200 if the first one had finished)" ;;
esac
st=""
for i in $(seq 1 40); do
    S=$(op carol BlobDiff.PurgeStatus "" "$(jq -cn --arg c "$CMD" '{commandId: $c}')")
    st=$(jq -r .state <<<"$S")
    case $st in COMPLETED | ABORTED) break ;; esac
    sleep 3
done
check "purge state" COMPLETED "$st"
verdict INFO "purge status: $(jq -c . <<<"$S")"
check "no error diff left on S19" 0 "$(diffs "$S19" | jq '[.[] | select(.properties["bdiff:status"] == "error")] | length')"
check "the ok diff of S19 is kept" 1 "$(diffs "$S19" | jq '[.[] | select(.properties["bdiff:status"] == "ok")] | length')"
check "the other statuses are kept (S08)" 3 "$(diffs "$S08" | jq length)"
verdict INFO "CleanEmptyFolders: $(op carol BlobDiff.CleanEmptyFolders "" '{}')"
check "PurgeStatus of an unknown command" UNKNOWN \
    "$(op carol BlobDiff.PurgeStatus "" '{"commandId": "not-a-command"}' | jq -r .state)"
```

The purge with `status=error` and `before=tomorrow` removes **every** error diff of the instance,
those of earlier runs included. That is intended on this local test instance.

A second purge answering 500 instead of 409 means a plugin older than item 108 on a Nuxeo hotfix
older than NXP-33925 (2025.24.15 is one): the refusal is right, its status is not.

## 7. Report

Write `test-campaign/results/agent-report-$RUN.md` with:

1. A header: date, `$NX`, run id, the plugin version (`GET /api/v1/automation/BlobDiff.Retry` is
   enough to prove it is installed), whether OpenSearch answered the facets.
2. A summary table: one row per scenario (S01 … S28, P1, P2, A1 … A5) with `PASS`, `FAIL`,
   `FINDING`, or `NOT RUN`, and the count of checks.
3. Every `FAIL` and `FINDING`, each with: the step, the expected outcome, the observed one (copy the
   line of `results/agent-run-$RUN.tsv`), and the raw files of `results/raw-$RUN/` for that step.
   Say whether it looks like a plugin defect, a deployment issue, or a wrong expectation in this
   runbook — do not try to fix anything.
4. The RTF and YAML checks, explicitly (probes in earlier runs): S11 `-03` (RTF sent as `text/rtf`),
   P1 (as `application/rtf`), P2 (YAML sent as `application/yaml`).
5. What remains for a human (§ 8).

Never put a password in the report. Document ids and the run id are fine.

## 8. Left to a human (Web UI)

Unless you drive a browser, these stay manual; list them in the report as `NOT RUN`. They are § 3
Part F of `runbook.md`:

- the drawer entry "Content changes audit" and the "Content changes" tab: visible to carol, absent
  for alice and bob; `#!/search/blobdiffs` typed by alice shows an empty pane;
- the `BlobDiff` view: colored diff, "Show more" past 1 000 lines (S21), the "Format: x → y" badge
  (S08, S09, S11, S12), the "Content replaced" explanation (S08 `-02`), **no Edit** action, the source
  badges *In trash* / *Source deleted* / *Source not accessible to you* (S26, S27);
- the delete and purge dialogs: the acknowledgment checkbox, the progress of the purge; two deletions
  of a dozen diffs in a row, with no "Delete failed" and no row shown twice (F11);
- the known diff-to-diff navigation issue: opening a `BlobDiff` from another one keeps the previous
  one on screen until a reload (open item, not a regression).

## 9. Cleanup (only if the user asks)

Everything the run created carries `$RUN` in its name. To remove it, as `Administrator`: delete the
documents of the workspace whose name ends with `-$RUN`, then purge their diffs from the Web UI or
with `BlobDiff.Delete` on the ids of `diffs <docId>`. The users, the group and the containers are
kept for the next run.

