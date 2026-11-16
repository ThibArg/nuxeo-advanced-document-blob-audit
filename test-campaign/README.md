# End-to-End Test Campaign

The unit tests of the plugin run on an in-memory repository. This campaign tests the **installed
package** on a running Nuxeo LTS 2025 (Docker Compose, MongoDB, OpenSearch, Web UI): real uploads and
versions, the background work, the search engine, the permissions and the auditor screens.

Every step states its expected outcome. The expectations live in the two runbooks only, which state the
same results: this page says which one to follow and what to prepare. Run the campaign on a build you
are about to ship, after upgrading Nuxeo or Web UI, or on a fork.

> [!WARNING]
> Use a **local, disposable** instance, never a shared or production one. The campaign configuration
> turns the feature on for every `File` document and every blob field, and the campaign creates three
> test users (`alice`, `bob`, `carol`), a group (`auditors`), a workspace and a restricted folder.

## What It Covers

| Part | Scenarios | What |
|---|---|---|
| A — core formats | S01–S06 | Word, Excel, PowerPoint, PDF, with and without images |
| B — text family | S07–S10 | plain text, JSON replaced by XML, a `.txt` saved as `.csv`, Markdown, YAML |
| C — formats replacing one another | S11–S14 | the Word family (`.docx`, `.odt`, `.doc`, `.rtf`), `.xls` replaced by `.xlsx`, format changes, legacy `.ppt` |
| D — limits, skips and failures | S15–S22 | an Excel cell at its maximum size, over `maxBlobSize`, an unsupported format, cases where nothing may be audited, a corrupted file and its retry, over `maxLines`, over `maxDiffEntries`, a rewrite in the same format |
| E — attachments, users, permissions, lifecycle | S23–S28 | `files:files`, two authors and an automatic version, a type out of scope, a source the auditor cannot read, a source trashed then deleted, another charset |
| F — auditor screens and operations | F1–F11 | drawer entry, tab, audit search and facets, `BlobDiff` view, retry, deletion, purge |

## Before You Start

1. Build the package at the root of the repository (`mvn clean install`), and install
   `nuxeo-advanced-document-blob-audit-package/target/nuxeo-advanced-document-blob-audit-<version>.zip`
   in the Nuxeo container.
2. Mount [`nuxeo-config/blobaudit-runbook-config.xml`](nuxeo-config/blobaudit-runbook-config.xml) so that
   it ends in `nxserver/config`, then restart Nuxeo. It turns the feature on for `File` documents,
   watches every blob xpath, turns the image inventory on and names the auditors group `auditors`. The
   size limits keep their defaults on purpose: the test files are sized against them.
3. Use the OpenSearch search client: the facets of the audit search need it.

The agent does none of this: it expects the package installed and the configuration in effect. Step by
step, with the checks to make after the restart: [`runbook.md` § 1.1](runbook.md#11-deploy-the-plugin-and-its-configuration).

## Two Ways to Run It

| | By hand | By an agent |
|---|---|---|
| Runbook | [`runbook.md`](runbook.md) | [`runbook-by-agents.md`](runbook-by-agents.md) |
| Through | Web UI | the REST API of `localhost` only, with [`agent/blobaudit-helpers.sh`](agent/blobaudit-helpers.sh) (`bash`, `curl`, `jq`) |
| Scenarios | S01–S28, F1–F11 | S01–S28, two more mime-type checks (P1, P2), the auditor operations over the REST API (A1–A5) |
| Users, group, workspace | created by hand (§ 1.2, § 1.3) | checked, created when missing |
| Passwords | any local test password | `NX_ADMIN_PWD` and `TEST_PWD`, exported or in `~/.config/blobaudit-test.env` (mode `600`); never written in the repository nor in the results |
| Auditor screens | part F | left to a person, reported as `NOT RUN` |
| Results | the checklist at the end of the runbook | `results/`, ignored by git: one log line per check, the raw responses, `agent-report-<run>.md` |

By hand, allow about 2 h for everything, 30 min for part A. What is known and not to be reported as a
new bug: [`runbook.md` § 5](runbook.md#5-known-behaviours-not-to-be-reported-as-new-bugs).

### With an Agent

Export the two passwords (or write them in `~/.config/blobaudit-test.env`), start your coding agent at
the root of the repository, and ask it:

> Follow `test-campaign/runbook-by-agents.md`.

The agent first checks that Nuxeo answers on `localhost`, that the package is installed and that
`/change-diff` exists, and stops otherwise; a smoke test on plain text then checks that the feature is
on. It never modifies the repository, Nuxeo or the Docker files, and writes under `results/` only. A
failed check does not stop the run: it is logged, and the agent goes on. Every document it creates
carries the run id in its name, so two runs never collide, and an interrupted run resumes with its
saved ids.

## What Is in This Folder

| Path | What |
|---|---|
| `S01-…` to `S28-…` | the test files, one folder per scenario: `xxx-00` is the first upload, `-01`, `-02`, `-03` the next ones |
| `nuxeo-config/blobaudit-runbook-config.xml` | the configuration to deploy (feature on, `File` only, every blob xpath, image inventory, `auditors` group) |
| `MANIFEST.tsv` | every file with its size, SHA-256 and the mime type a browser sends |
| `runbook.md` | the campaign by hand, in Web UI |
| `runbook-by-agents.md` | the same campaign, scripted over the REST API for an agent |
| `agent/blobaudit-helpers.sh` | the bash functions (`curl`, `jq`) the agent runbook uses |
| `_generator/` | the Python scripts that produced the files (to regenerate or tweak them), and `make-large-files.sh` |
| `results/` | what an agent run writes (log, raw responses, report); ignored by git |

## The Test Files

- They are committed and must stay **byte-stable**: `MANIFEST.tsv` records their SHA-256, and the
  expected results depend on their exact content.
- The two S16 files over 10 MB (`too-large-00.txt`, `too-large-01.txt`) are not in git. Before S16, run
  once, from the root of the repository:

  ```bash
  bash test-campaign/_generator/make-large-files.sh
  ```

  It writes them and checks their SHA-256 against `MANIFEST.tsv` (about a second). The agent helpers
  run it on their own.
- To change the test material, see the header of [`_generator/generate.py`](_generator/generate.py).
  Regenerating rewrites the LibreOffice files (`.odt`, `.doc`, `.rtf`, `.ppt`) with new bytes: review
  the diff, and update both runbooks when an expected result changes.
