# bdq_workbench

BDQ Workbench is a Java 17 application for policy-driven Biodiversity Data Quality (BDQ) execution over Darwin Core Archives (DwC-A) and Darwin Core Data Packages.

BDQ Workbench takes DarwinCore Archive files or Darwin Core Data Package files as input, identifies tests that apply to a bdqffdq:UseCase (purpose to which data are to be put and need to have fitness for) that are available in the implemntation, then runs those tests on the data (in pre-amendment, amendment, and post-amendment phases) and produces a data quality report (in several formats).  It also evaluates the binding of information elements in the input data with the test implementations, can provide parameters to parameterized tests, and can run tests from a use case individually.

See: https://bdq.tdwg.org/

## Build and test

```bash
mvn -q clean package
```

```bash
mvn -q test
```

Run integration tests only (failsafe):

```bash
mvn -q verify -Prelease
```

## Run

```bash
mvn -q package
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar
```

Launching the jar without options opens a desktop GUI for entering parameters and monitoring execution.
The startup screen includes a dataset file picker, use-case selection, and advanced options for custom use-case/test-definition/ontology sources, discovery packages, and threads. By default the GUI caches:

- `https://bdq.tdwg.org/draft/dist/bdquc.xml` (use cases)
- `https://bdq.tdwg.org/draft/dist/bdqtest.ttl` (test definitions)
- `https://bdq.tdwg.org/draft/vocabulary/bdqffdq.ttl` (ontology)

Use-case and RDF definition inputs support RDF/XML, Turtle, and JSON-LD serializations.
Logging is configured to the console with a default `DEBUG` root level in `src/main/resources/logback.xml`.

Show command-line help:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar --help
```

Configuration defaults are in `src/main/resources/application.properties` and can be overridden with CLI options, for example:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar --dataset path/to/dataset.zip
```

A dataset with more than one table (a Darwin Core Archive with extensions, a data package with
several resources) always runs through a *dataset view*. You can supply one with `--dataset-view`
(`bdq.dataset.view` in config), or build it interactively in the GUI with `Build Dataset View...`:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar \
  --dataset path/to/datapackage.json \
  --dataset-view path/to/bdq-dataset-view.json
```

A view picks a grain table (one execution record per row), joins directly related tables, and maps
each Darwin Core term to one source table and column. Each join says what to do when a grain record
has more than one related row (for example, several identifications of one occurrence):

| Policy | Effect |
|---|---|
| `FIRST_ROW` (default) | Flatten: use the first related row and ignore the rest. |
| `EXPAND` | Keep every related row. A test that reads any term mapped from this table runs once per related row, with the grain record's (and flattened tables') terms reused for each; VALIDATION and ISSUE results are also rolled up to the grain record. A grain record with no related rows is tested once with those terms blank. |
| `AGGREGATE` | Flatten: join all related rows' values with `" \| "`. Combined values usually fail tests on that term. |
| `REJECT` | Flatten: leave the mapped terms empty, with a warning, for grain records that have more than one related row. |

For example, with occurrence as the grain, join event with `FIRST_ROW` and identification with
`EXPAND`: a test such as VALIDATION_DATEIDENTIFIED_INRANGE then runs once per identification, each
time with that identification's `dateIdentified` and the occurrence's event date. A test whose inputs
come from two different `EXPAND` tables cannot run and reports an error, so expand only the tables
whose rows each need testing. The builder shows the multiplicity observed in the data for each join,
previews expanded rows beneath each grain record, and warns where a policy drops or garbles rows.

When a term is mapped both from the grain and from an `EXPAND` table — for example, an occurrence
core that carries its current identification and an identification-history extension — both are
tested: the grain's value once, and each expanded row's value once, all rolled up to the record.

Without `--dataset-view`, the workbench builds the view itself: the grain is the table it would read
anyway (occurrence when there is one), every column of the grain and its directly related tables is
mapped, and related tables with at most one row per grain record (such as the event of an
occurrence) are joined `FIRST_ROW`. For each related table that has more than one row for some
record, you decide how to handle it:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar \
  --dataset path/to/dataset.zip \
  --join-policy identification=EXPAND --join-policy multimedia.txt=FIRST_ROW
```

`--join-policy` is repeatable (`bdq.dataset.join.policies=identification=EXPAND,multimedia.txt=FIRST_ROW`
in config). Any table still undecided is asked about on the console when running interactively; in
a script or CI the run stops before testing and lists each such table with its observed rows per
record. In the GUI, the dataset view builder opens with those tables highlighted, and `Use View`
saves the view to `reports/bdq-dataset-view.json` and continues the run.

Configuration precedence is:

1. command-line or GUI-supplied overrides
2. `src/main/resources/application.properties`
3. built-in fallback defaults in `ConfigLoader`

Pre-execution record filtering can be configured from the CLI with repeatable `--record-filter` flags, or in the GUI with the `Build Record Filters...` dialog after selecting a dataset. Filter syntax is:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar \
  --record-filter 'dwc:genus=Abies|Pinus' \
  --record-filter dwc:country=Canada
```

Within a record-filter, search terms are related by OR; across record-filters, terms are related by AND.  The example above is interpreted as dwc:genus=(Abies OR Pinus) AND dwc:country=Canada.  Field names match case-insensitively by full name or local name (for example `dwc:country` and `country`), while search term values are matched exactly and case-sensitively against the canonicalized input values.

### Processing pipeline and flow-control options

The current desktop/CLI flow can be summarized as:

```text
Dataset input
   |
   +--> Load DwC-A zip / Data Package CSV
   |
   +--> [optional] Build dataset view and/or record filters from dataset terms/values (GUI:
   |               schema/relationship pane, use-case-driven term mapping pane, flattened preview)
   |
   +--> [optional] Apply record filters
   |
   +--> Resolve use case / policy tests
   |
   +--> Discover implementations on classpath
   |
   +--> Bind tests + validate parameter mappings
   |       |
   |       +--> unresolved tests may still be shown in preflight
   |       +--> GUI can continue with runnable tests only ("Start Available Tests")
   |       +--> GUI can edit/load/save parameter values before execution
   |
   +--> [optional] Distinct-value aggregation (`bdq.execution.dedup=true`)
   |
   +--> PRE_AMENDMENT tests
   |
   +--> AMENDMENT tests
   |
   +--> POST_AMENDMENT tests
   |
   +--> Post-process responses
   |       |
   |       +--> built-in COUNT multi-record measures
   |       +--> synthesized `UNABLE_TO_RUN` responses for unresolved/unbound tests
   |
   +--> Export text, RDF/Turtle, XLSX, and unresolved-response XLSX reports
```

Current flow-control options are intentionally modest:

- **Dataset choice**: choose a DwC-A zip or Darwin Core Data Package.
- **Record filtering**: optional exact-match filtering before binding or execution; in the GUI the filter builder inspects the selected dataset and lets the user choose only from terms present in the data.
- **Use case/test-definition/ontology sources**: GUI advanced options and CLI/config can override the default RDF sources.
- **Implementation packages**: CLI/config/GUI advanced options control the discovery package roots.
- **Thread count**: CLI/config/GUI advanced options control the worker pool size.
- **Continue with unresolved tests**: GUI preflight can proceed with runnable tests even when some policy or implementation bindings remain unresolved.
- **Parameter overrides**: GUI preflight supports per-test parameter editing plus saving/loading parameter settings.
- **Isolated test execution**: GUI preflight/debug tools can run one bound test independently against the prepared dataset.
- **Distinct-value reduction**: CLI/config `bdq.execution.dedup` and the GUI's `Reduce repeated test calls by distinct input values` checkbox toggle whether eligible bindings run once per distinct input-value group instead of once per record.
- **External-service scheduling and resilience**: `bdq.execution.*` settings (and the CLI's `--external-concurrency`, `--retries`, `--reuse-pre-results`, `--execution-override`, `--lane-limit`) control per-resource concurrency lanes, adaptive throttling, circuit breaking, retries, and PRE-to-POST result reuse; see [Execution scheduling and resilience](#execution-scheduling-and-resilience).
- **Not currently supported**: there is still no user-facing phase skip/select control, and the main run UI still does not expose cancellation.

## Architecture overview

The codebase is organized under `org.filteredpush.bdq_workbench` with explicit module boundaries:

- `app`: bootstrap, configuration loading, orchestration, exception handling
- `model`: domain model (`UseCase`, `Policy`, `TestDefinition`, `ImplementationBinding`, `Phase`, `Response`)
- `ingest`: DwC-A and Data Package ingestion into canonical records
- `rdf_policy`: use-case/policy/test RDF resolution
- `test_discovery`: annotation-based discovery (`@Provides`, `@Validation`, `@Issue`, `@Measure`, `@Amendment`, etc.) and binding
- `execution`: parallel phase orchestration (pre-amendment, amendment, post-amendment) with deterministic ordering and distinct-value test call reduction
- `reporting`: summary output, normalized response stream export, RDF export, and XLSX spreadsheet export (via kurator-ffdq)

Extension points are interfaces for discovery, binding, execution adapters, and report exporters.

## Input binding and parameter handling

Execution binding is reflection-driven and annotation-aware:

- `@ActedUpon("dwc:term")` and `@Consulted("dwc:term")` are matched against canonical record fields.
- Matching is deterministic across exact values, `dwc:prefix` values, and local-name forms such as `eventDate`.
- `@Parameter(name = "bdq:...")` values come from the selected test definition / UI parameter editor.
- Legacy implementations that accept `(Map record)` or `(Map record, Map parameters)` are still supported for backward compatibility.

For every candidate implementation method the workbench records:

- implementation status: `FOUND`, `MISSING`, or deterministic resolution of an ambiguous set
- binding status: `BOUND`, `PARTIAL`, or `UNBOUND`
- per-parameter diagnostics for missing Darwin Core terms, missing user parameters, or unsupported parameter types

When both default and parameterized implementations exist for the same test, the workbench prefers:

1. the parameterized method when the user provides parameter values
2. the default method when no parameter values are provided

The preflight grid shows the chosen method, parameterization capability, and whether the selected run is using default values.

## Response stream semantics

Each execution result is normalized into a response stream entry with:

- record id
- test id and test type
- implementation class/method provenance
- phase (`PRE_AMENDMENT`, `AMENDMENT`, `POST_AMENDMENT`)
- parameter values used for the invocation
- `responseStatus`
- `responseResult`
- `comment`
- amendment payload, when present

`DQResponse` objects are adapted reflectively by reading `getResultState()`, `getValue().getObject()`, and `getComment()`. Amendment results are preserved as normalized amendment maps and then applied to the amendment working copy before post-amendment execution.

Reports include:

- `reports/bdq-report-summary.txt` Human readable summary of test execution results.
- `reports/bdq-report-responses.txt` Human readable list of test execution Response values.
- `reports/bdq-report-rdf.ttl` RDF test responses serialized as Turtle.
- `reports/bdq-report-xls.xlsx` Spreadsheet report produced via kurator-ffdq's `XLSXPostProcessor` (see below).
- `reports/bdq-report-xls-unresolved.xlsx` Spreadsheet companion listing unresolved, unbound, and other sentinel-record responses excluded from the main per-record workbook.

The structured HTML and Markdown reports are quality-control summaries sized for real datasets:
high-impact action items (issues, non-compliance and its most frequent causes, the amendment
proposals that most reduced problems, empty terms), pre/post measure differences, records with quality for the use case (all multi-record QA measures COMPLETE), a per-test table of
problems before and after amendment, information elements empty in every record, proposed
amendments ranked by how many records they affect, and a capped list of records needing
attention. Tests are named by their labels, and records by values from the original data —
`institutionCode:collectionCode:catalogNumber` (or dataset and catalog number) plus the data file
and line within the archive or package. Report headers, and the text summary, state any results
with external prerequisites not met. The full per-record results remain in the spreadsheet and
the response list (`bdq-report-responses.txt`, which leads with record and test label columns).

The input is checked for the record-level markers of the BDQ
[Guide to Marking and Identifying Synthetic and Modified Data](https://rs.tdwg.org/bdq/doc/synthetic/):
`collectionCode` "Synthetic Example" / "Modified Example", the guide's two `collectionID` UUIDs,
`relationshipOfResource` "source for modified example record", and the `example.org`
`institutionCode`/`institutionID`. The raw input rows are scanned (the grain table and its related
rows, before any view mapping), so markers are found even when a view does not map those terms.
When any record is marked, the structured HTML and Markdown reports open with a warning, the text
summary starts with one, the RDF report carries the counts and an `rdfs:comment`, the GUI preflight
summary and the CLI output show it too; every one of these also states the result ("none detected
in N input record(s)") when nothing is marked.

## Distinct-value execution (test call reduction)

A BDQ test is specified as a pure function of the Darwin Core terms it declares as input
(`@ActedUpon`/`@Consulted`), so records that share identical values for exactly those terms must
produce identical results. Rather than invoking a test once per record, `ParallelPhaseExecutionService`
partitions each phase's records into distinct-value groups per binding (via `RecordGroupPartitioner`),
invokes the test once against one representative record per group, and copies that one response to
every record in the group — the final response list has exactly the same shape (one response per
record per test) as running per-record would, just with fewer real invocations.

A few behaviors worth knowing about:

- **Grouping is shared across tests, not just per test.** Two bindings that happen to declare the
  same set of term names — regardless of test type, or whether a term is `ACTED_UPON` for one and
  `CONSULTED` for the other — share the same partitioning work for a phase via a `PhaseGroupCache`,
  rather than each recomputing it from scratch. E.g. ten different validations that each act upon
  only `dwc:country` all reuse one partition of the dataset's distinct country values.
- **Grouping is exact-match only.** Term values are compared with plain string equality — no
  case-folding or other normalization — since many BDQ tests are sensitive to exact formatting.
- **Amendments are sequenced correctly within the AMENDMENT phase.** Since one amendment test's
  output can change values a later amendment test in the same phase groups or reads by,
  AMENDMENT-phase bindings are processed one at a time: each binding's groups are computed,
  invoked, and its resulting amendments applied to every group member, and any cached partition
  touching the changed fields is discarded, before the next binding's groups are computed.
  PRE_AMENDMENT and POST_AMENDMENT never mutate records mid-phase, so their bindings' groups are
  all computed up front and dispatched round-robin across bindings (see below).
- **Not every binding is eligible.** Implementations bound via the legacy `(Map record)`/
  `(Map record, Map parameters)` signatures read the whole record or parameter map rather than
  specific declared terms, so the workbench can't know what subset of fields they actually depend
  on — these always run once per record.
- **Configurable via `bdq.execution.dedup`** (CLI `--dedup true|false`, default `true`). Disabling
  it runs every binding once per record exactly as if none were dedup-eligible, useful for
  debugging or comparing behavior against the pre-reduction execution path.

## Execution scheduling and resilience

The workbench cannot know beforehand whether a discovered test implementation calls an external
service (WoRMS, IRMNG, GBIF, a geography layer server, ...). Running every group of one test on
every worker at once can send a burst of simultaneous requests to that service; the service then
throttles or rejects them, implementation libraries retry immediately, and whole test runs end in
`EXTERNAL_PREREQUISITES_NOT_MET`. `ParallelPhaseExecutionService` therefore schedules invocations
as follows (all without changing which responses are produced or their order):

1. **Fair dispatch.** In PRE_AMENDMENT and POST_AMENDMENT every binding's distinct-value groups
   are planned first and then dispatched round-robin — group 1 of test A, group 1 of B, group 1 of
   C, group 2 of A, ... — so one test cannot fill every worker. AMENDMENT bindings still run one at
   a time, in order.
2. **Resource lanes.** Each binding is assigned a resource lane (`ExecutionResourceClassifier`),
   in this order of precedence: an explicit override for its test ID, implementation signature,
   `class#method`, or class; then a source-authority-like parameter (`bdq:sourceAuthority`,
   `bdq:taxonIsMarine`, `bdq:geospatialLand`, ...), which marks it *likely external* and names
   the lane after the authority (`source:worms`), so different tests using one service share a
   lane; then clearly local method names (`...Notempty`, `...Inrange` without a source authority),
   which run *local*; everything else is *unclassified* and gets a lane per `class#method`. A lane
   runs at most its limit of invocations at once; work waiting on a lane stays in the scheduler's
   queue and never occupies a worker, so unrelated lanes and local work keep running. These static
   hints are only a starting point: overrides replace them, and a lane not pinned by an override
   that has run many invocations without an external failure (a "source authority" can be a
   bundled local data layer) is promoted to the local limit.
3. **Runtime adaptation and circuit breaker.** `ResponseFailureClassifier` sorts each response
   into completed, likely transient external (timeouts, connection failures, unknown host,
   HTTP 429/5xx), non-transient configuration (invalid/unsupported source authority,
   HTTP 400/401/403/404), ambiguous external (`EXTERNAL_PREREQUISITES_NOT_MET` without a clear
   diagnostic), or internal. A transient or ambiguous external failure halves the lane's limit
   (minimum 1); successes restore it one slot at a time. After consecutive external failures the
   lane's circuit opens: its queued work pauses, without holding workers, for a cooldown that
   doubles on each reopening; then one probe runs, and a successful probe closes the circuit and
   resumes the lane gradually. A lane that keeps failing after several openings is given up for
   the run, and its remaining work is reported as `EXTERNAL_PREREQUISITES_NOT_MET` without being
   invoked. Configuration failures never throttle, open a circuit, or retry.
4. **Retries.** A group whose response is a likely transient external failure is retried (the
   group, not each record), after an exponential backoff with jitter scheduled on a timer rather
   than a sleeping worker; an ambiguous failure is retried at most once. The retry rejoins its
   lane's queue and obeys the lane's current limit and circuit. Only the final attempt's response
   is fanned out to the group's records; its message notes the attempt count (the comment and
   status are the implementation's own), so a group that never recovers still reports the latest
   `EXTERNAL_PREREQUISITES_NOT_MET` diagnostic. An AMENDMENT group's retries settle before its
   amendments are applied and before the next amendment binding runs.
5. **PRE-to-POST reuse.** POST_AMENDMENT re-runs every PRE_AMENDMENT validation, issue, and
   measure that has no explicit POST_AMENDMENT binding. When amendments left all of a group's
   inputs unchanged, the successful PRE_AMENDMENT result is copied into POST_AMENDMENT (with the
   POST_AMENDMENT phase, fresh timestamps, and a note in its message) instead of invoking the
   test again. An invocation's fingerprint covers the implementation signature, test ID and type,
   parameter values, the governing relation of the evaluated subject, and the value of every
   declared `ACTED_UPON`/`CONSULTED` term. Errors, unable-to-run results, and every
   `EXTERNAL_PREREQUISITES_NOT_MET` result are never reused; amendments, explicitly POST-bound
   tests, legacy whole-record bindings, and bindings with an unresolved input are always invoked;
   reuse is off when `bdq.execution.dedup` is `false`.

Lane events (throttled, capacity restored, circuit opened/half-open/closed, retry scheduled,
succeeded, or exhausted) are logged and reported to `ExecutionProgressListener.onResourceLaneEvent`;
at the end of a run the per-resource and per-test statistics (invocations, maximum concurrency,
external failures, retries, recovered and exhausted retries, circuit openings, reused and skipped
calls) are logged and passed to `ExecutionProgressListener.onExecutionStatistics`.

Settings (`application.properties`, overridable like any other setting):

| Setting | Default | Meaning |
| --- | --- | --- |
| `bdq.threads` | `4` | Worker pool size (unchanged) |
| `bdq.execution.lanes` | `true` | Per-resource lanes (`false`: one shared lane using the whole pool) |
| `bdq.execution.concurrency.external` | `2` | Limit of a likely external lane (CLI `--external-concurrency`) |
| `bdq.execution.concurrency.unclassified` | `4` | Limit of an unclassified lane |
| `bdq.execution.concurrency.local` | `0` | Limit of a local lane; `0` = the whole worker pool |
| `bdq.execution.adaptive` | `true` | Adaptive throttling and circuit breaker |
| `bdq.execution.circuit.failures` | `3` | Consecutive external failures that open a circuit; `0` disables it |
| `bdq.execution.circuit.cooldown.ms` / `.max.ms` | `5000` / `60000` | Initial and maximum circuit cooldown |
| `bdq.execution.retries` | `2` | Workbench retries of a transient failure; `0` disables (CLI `--retries`) |
| `bdq.execution.retry.delay.ms` / `.max.ms` | `500` / `8000` | Base and maximum retry backoff (reduced by up to half at random) |
| `bdq.execution.reuse` | `true` | PRE-to-POST result reuse (CLI `--reuse-pre-results`) |
| `bdq.execution.overrides` | empty | `target=spec;...`, spec from `external`/`local`/`unclassified`, `lane:NAME`, `max:N` (CLI `--execution-override`, repeatable) |
| `bdq.execution.lane.limits` | empty | `laneKey=N;...`, e.g. `source:worms=1` (CLI `--lane-limit`, repeatable) |

Every limit is capped at the worker count. For example, to put every test of the georeference
library that consults WoRMS into one lane that makes one call at a time:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar --dataset occurrences.zip \
  --execution-override 'org.filteredpush.qc.georeference.DwCGeoRefDQDefaults#validationCoordinatesTerrestrialmarine=external,lane:worms' \
  --lane-limit lane:worms=1
```

Programmatically, pass an `ExecutionPolicy` (built with `ExecutionPolicy.builder()`) to the
`ParallelPhaseExecutionService(threads, adapter, listener, dedup, policy)` constructor. The older
constructors remain and use the same defaults except that PRE-to-POST reuse is off, so existing
callers see every POST_AMENDMENT invocation they did before; the CLI and GUI use the policy from
their configuration (the GUI takes it from `application.properties` and any `--gui` overrides).
Lanes, adaptive state, and statistics are scoped to one `execute` call, and the worker pool, the
timer, and every queued, delayed, or in-flight invocation are shut down when it returns, fails, or
is cancelled.

## Spreadsheet (XLSX) report export

`XlsxReportExporter` builds an in-memory kurator-ffdq `FFDQModel` directly from the run's
`ExecutionSummary` — one data resource per input record and one response per `Response` — and
streams it directly to `bdq-report-xls.xlsx` with kurator-ffdq's `XLSXPostProcessor`, which
produces `Summary`, `Initial Values`, `Final Values`, `Measures`, `Validations`, `Amendments`, and
`Issues` sheets, with per-record rows color-coded by outcome.

A few behaviors worth knowing about:

- **Missing information elements are padded, not omitted.** If a use case's tests expect a Darwin
  Core term as an input information element (acted upon or consulted) and that term isn't present
  in the input data at all, it still appears as a column in the report, with an empty value for
  every record — rather than being silently missing from the spreadsheet. This comes from the
  run's test/implementation bindings (`ExecutionSummary.bindings()`), not from re-resolving the
  ratified ontology, so it reflects exactly what the bound implementations look for.
- **Responses that don't apply to one record** — built-in multi-record measures and synthesized
  unresolved/unbound placeholder responses — are excluded from `bdq-report-xls.xlsx` and instead
  listed in their own small companion workbook, `bdq-report-xls-unresolved.xlsx` (via
  `UnresolvedResponsesExporter`), since kurator-ffdq's per-record model has no place for them. This
  is a separate file rather than an extra sheet appended afterward: appending would require
  reopening the (potentially very large) written workbook as a plain `XSSFWorkbook`, which for a
  large dataset can exceed Apache POI's single-zip-entry read cap and fail with a
  `RecordFormatException`. Keeping the two files separate means the main export is a pure,
  one-pass stream straight to disk regardless of dataset size.
- **Issues sheet coloring is a known gap.** kurator-ffdq's `Issue` context class lacks a no-arg
  constructor (unlike `Measure`/`Validation`/`Amendment`), which breaks RDFBeans deserialization if
  one is attached to a saved response. `XlsxReportExporter` leaves it unset, so ISSUE-type
  responses still get their row and every field column, just without per-cell acted-upon/consulted
  coloring.
- **Build dependency:** this depends on kurator-ffdq's "restored and productized"
  `XLSXPostProcessor`, which as of this writing only exists in a `3.3.0-SNAPSHOT` build (the
  `pom.xml` dependency is pinned there, with a comment to move it to a released `3.3.0` once one is
  cut). Building this project currently requires that SNAPSHOT installed locally.

## Multi-record measure preparation

`ExecutionSummary` exposes filtering and counting helpers over the normalized response stream so downstream multi-record measure work can count and filter by:

- phase
- test type
- response status
- response result

This is the initial plumbing layer for multi-record calculations. Full multi-record execution remains a follow-up item, but downstream code can now consume the normalized response stream instead of raw input rows.

## GUI workflow

The desktop GUI supports:

1. selecting a dataset and use case
2. optionally opening `Build Record Filters...` to inspect dataset terms and common values, then assembling exact-match filters
3. running a preflight review that loads the dataset, applies filters, discovers implementations, and populates a test grid
4. reviewing binding status, method selection, parameterization capability, and normalized filter counts before execution
5. editing parameter values or keeping defaults before execution
6. saving and loading parameters for parameterized tests
7. continuing with runnable tests only when some tests remain unresolved (`Start Available Tests`)
8. running a bound test in isolation
9. monitoring a simple stage list plus live per-phase progress and response/result counters
10. reviewing a post-run summary and saved output locations

The execution phases are fixed (`PRE_AMENDMENT`, `AMENDMENT`, `POST_AMENDMENT`) and are not currently user-skippable from either the CLI or the GUI.

When policy resolution or implementation binding cannot produce a runnable test, the workbench still emits synthesized `UNABLE_TO_RUN` responses so those tests appear in the final summary and unresolved workbook outputs.


## Development

### AI-assisted development disclosure

This project has used GitHub Copilot and Claude Code as AI coding assistants during development.

Copilot and Claude contributions are limited to suggested code and documentation text.  
All accepted changes were reviewed, edited as needed, and validated by human maintainers before 
inclusion in the master branch.

#### Provenance and responsibility

- Human maintainers are responsible for all design decisions, semantics, and released content.
- AI-generated suggestions are treated as draft material and may contain errors.
- Ontology-aligned terminology and normative language in this project are curated by the tdwg/bdq maintainers.
