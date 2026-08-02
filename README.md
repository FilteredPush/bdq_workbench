# bdq_workbench

BDQ Workbench is a Java 17 application scaffold for policy-driven Biodiversity Data Quality (BDQ) execution over Darwin Core Archives (DwC-A) and Darwin Core Data Packages.

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
- `reporting`: summary output, normalized response stream export, and XLS compatibility hook

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

Reports now include:

- `reports/bdq-report-summary.txt`
- `reports/bdq-report-responses.txt`
- `reports/bdq-report-xls-hook.txt`

## Multi-record measure preparation

`ExecutionSummary` now exposes filtering and counting helpers over the normalized response stream so downstream multi-record measure work can count and filter by:

- phase
- test type
- response status
- response result

This is the initial plumbing layer for multi-record calculations. Full multi-record execution remains a follow-up item, but downstream code can now consume the normalized response stream instead of raw input rows.

## Updated GUI workflow

The desktop GUI now supports:

1. selecting a dataset and use case
2. running a preflight review that discovers implementations and populates a test grid
3. reviewing binding status, method selection, and parameterization capability
4. editing parameter values or keeping defaults before execution
5. monitoring live per-phase progress and response/result counters
6. reviewing a post-run summary and saved output locations
