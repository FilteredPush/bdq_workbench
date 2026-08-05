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

## Architecture overview

The codebase is organized under `org.filteredpush.bdq_workbench` with explicit module boundaries:

- `app`: bootstrap, configuration loading, orchestration, exception handling
- `model`: domain model (`UseCase`, `Policy`, `TestDefinition`, `ImplementationBinding`, `Phase`, `Response`)
- `ingest`: DwC-A and Data Package ingestion into canonical records
- `rdf_policy`: use-case/policy/test RDF resolution
- `test_discovery`: annotation-based discovery (`@Provides`, `@Validation`, `@Issue`, `@Measure`, `@Amendment`, etc.) and binding
- `execution`: parallel phase orchestration (pre-amendment, amendment, post-amendment) with deterministic ordering
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

## Spreadsheet (XLSX) report export

`XlsxReportExporter` builds an in-memory kurator-ffdq `FFDQModel` directly from the run's
`ExecutionSummary` — one data resource per input record and one response per `Response` — and
renders it with kurator-ffdq's `XLSXPostProcessor`, which produces `Summary`, `Initial Values`,
`Final Values`, `Measures`, `Validations`, `Amendments`, and `Issues` sheets, with per-record rows
color-coded by outcome.

A few behaviors worth knowing about:

- **Missing information elements are padded, not omitted.** If a use case's tests expect a Darwin
  Core term as an input information element (acted upon or consulted) and that term isn't present
  in the input data at all, it still appears as a column in the report, with an empty value for
  every record — rather than being silently missing from the spreadsheet. This comes from the
  run's test/implementation bindings (`ExecutionSummary.bindings()`), not from re-resolving the
  ratified ontology, so it reflects exactly what the bound implementations look for.
- **Responses that don't apply to one record** — built-in multi-record measures and
  synthesized unresolved/unbound placeholder responses — are listed on an extra
  `"Unresolved & Multi-record"` sheet, since kurator-ffdq's per-record model has no place for them.
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
2. running a preflight review that discovers implementations and populates a test grid
3. reviewing binding status, method selection, and parameterization capability
4. editing parameter values or keeping defaults before execution
5. saving and loading parameters for parameterized tests
6. running a bound test in isolation
7. monitoring live per-phase progress and response/result counters
8. reviewing a post-run summary and saved output locations


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

