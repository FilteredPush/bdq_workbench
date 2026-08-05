# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

BDQ Workbench is a Java 17 desktop application for policy-driven Biodiversity Data Quality (BDQ)
test execution over Darwin Core Archives (DwC-A) and Darwin Core Data Packages. See
https://bdq.tdwg.org/ for the underlying BDQ framework/vocabulary this project implements against.

Given a dataset and a `bdqffdq:UseCase`, the workbench discovers which BDQ test implementations
are available (via classpath annotation scanning), binds the use case's policy tests to those
implementations, runs them across three phases (pre-amendment, amendment, post-amendment), and
exports a data quality report in several formats.

## Build and test

```bash
mvn -q clean package        # build the shaded jar
mvn -q test                 # unit tests only (surefire)
mvn -q verify -Prelease     # unit + integration tests (failsafe, *IT.java classes)
```

Run a single test class or method:

```bash
mvn -q test -Dtest=ReflectionExecutionAdapterTest
mvn -q test -Dtest=ReflectionExecutionAdapterTest#someMethodName
```

Integration tests (`*IT.java`, e.g. `WorkbenchFacadeIT`) are skipped by default (`dev` profile,
`skipITs=true`); the `release` profile flips `skipITs` to `false` and runs them via failsafe.

`kurator-ffdq` is currently pinned to a `3.3.0-SNAPSHOT` (see the dependency's comment in
`pom.xml`), so building this project requires that SNAPSHOT installed in the local Maven
repository first: `cd path/to/kurator-ffdq && mvn -q -DskipTests install`.

Run the application:

```bash
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar            # opens the desktop GUI
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar --dataset path/to/dataset.zip
java -jar target/bdq_workbench-0.1.0-SNAPSHOT.jar --help
```

Configuration defaults live in `src/main/resources/application.properties`
(`bdq.usecase.file`, `bdq.rdf.files`, `bdq.dataset`, `bdq.usecase.id`, `bdq.discovery.packages`,
`bdq.threads`) and are merged with CLI/GUI overrides by `ConfigLoader`. By default the GUI fetches
and caches use-case/test-definition/ontology RDF from `bdq.tdwg.org` (`CachedResourceResolver`);
RDF/XML, Turtle, and JSON-LD serializations are all supported. Logging is DEBUG-by-default to the
console via `src/main/resources/logback.xml`.

## Architecture

Code is organized under `org.filteredpush.bdq_workbench` with one package per pipeline stage.
`WorkbenchFacade` (`app/WorkbenchFacade.java`) is the orchestrator and the best entry point for
understanding how the stages connect — read its class Javadoc first. The pipeline, in order:

1. **`ingest`** — `IngestService` turns a DwC-A zip or Data Package into a `RecordDataset` of
   `CanonicalRecord`s (id + Darwin Core term map). `DwcArchiveIngestor` and `DataPackageIngestor`
   are the two format-specific readers.
2. **`rdf_policy`** — `PolicyResolverService` resolves a use-case identifier into an
   `ExecutionPlan` (the ordered, phase-tagged list of tests the use case calls for), reading the
   use-case XML and the `bdqtest.ttl`/`bdqffdq.owl` RDF definitions via `BdqSpecificationIndex` and
   `RdfDefinitionsLoader`. Tests the resolver can't resolve land in
   `ExecutionPlan.unresolvedTests()` rather than failing the run.
3. **`test_discovery`** — `ClasspathAnnotationTestDiscoveryService` scans configured Java packages
   (`bdq.discovery.packages`, default `org.filteredpush.qc`) with ClassGraph for ffdq-style
   annotations (`@Provides`, `@Validation`, `@Issue`, `@Measure`, `@Amendment`, etc.), producing
   `DiscoveredImplementation`s. It retries with progressively broader class loader strategies
   (context classloader → this class's loader → explicit `java.class.path` scan → ClassGraph
   default) since annotated classes may not be visible to the first strategy tried, depending on
   deployment (jar vs. IDE vs. shaded jar).
4. **binding** (`test_discovery/DefaultTestBindingService`) — matches each `ExecutionPlan` test to
   a discovered implementation, reflection-driven and annotation-aware:
   - `@ActedUpon("dwc:term")`/`@Consulted("dwc:term")` are matched against record fields
     deterministically across exact values, `dwc:`-prefixed values, and local-name forms
     (`eventDate`).
   - `@Parameter(name = "bdq:...")` values come from the selected test definition / UI parameter
     editor.
   - Legacy `(Map record)` / `(Map record, Map parameters)` signatures are still supported.
   - When both a default and a parameterized implementation exist for the same test, the
     parameterized one is preferred only if the user supplied parameter values; otherwise the
     default is used.
   - Every candidate records implementation status (`FOUND`/`MISSING`/ambiguous-resolved) and
     binding status (`BOUND`/`PARTIAL`/`UNBOUND`) plus per-parameter diagnostics, all surfaced in
     the GUI's preflight review grid before execution.
5. **`execution`** — `ParallelPhaseExecutionService` runs bound tests across records in phase
   order (`PRE_AMENDMENT` → `AMENDMENT` → `POST_AMENDMENT`) with deterministic ordering but
   parallel execution within a phase (`bdq.threads` workers).  `ReflectionExecutionAdapter` is the
   actual per-record invocation adapter: it builds a reflective argument array from the record's
   bound parameters, invokes the target method, and reads back an ffdq-style result purely
   reflectively (`getResultState()`, `getValue().getObject()`, `getComment()`) so this module has
   no compile-time dependency on ffdq result types. Amendments are detected two ways — a `Map`
   returned as the result's value, and (for AMENDMENT phase only) a before/after diff of the
   record's term map, since some implementations mutate the record in place instead of returning
   changed values. Any exception during argument binding or invocation is caught and turned into
   an `OutcomeStatus.ERROR` response rather than propagated.
6. **`reporting`** — `ReportingService`/`ReportExporter` implementations turn the final
   `ExecutionSummary` (normalized `Response` stream + `ExecutionSummaryMetadata`) into
   `reports/bdq-report-summary.txt` (human-readable summary), `reports/bdq-report-responses.txt`
   (human-readable response list), `bdq-report-rdf.ttl` (RDF/Turtle), `bdq-report-xls.xlsx`
   (Office Open XML spreadsheet, via `XlsxReportExporter`), and `bdq-report-xls-unresolved.xlsx`
   (a small companion workbook, via `UnresolvedResponsesExporter`). `XlsxReportExporter` builds an
   in-memory kurator-ffdq `FFDQModel` directly from the run's `ExecutionSummary` — one
   `DataResource` per input record, one `bdqffdq:*Response` per `Response` — rather than
   round-tripping through `RdfResponseExporter`'s Turtle output, and streams it directly to the
   output file with kurator-ffdq's `XLSXPostProcessor` (backed by `SXSSFWorkbook`, so it never
   holds the whole workbook in memory). A test's Darwin Core information elements (for per-field
   cell coloring, and for padding every record with an empty value for any field a use case's
   tests expect but the input data lacks entirely) are derived from `ExecutionSummary.bindings()`'s
   `ACTED_UPON`/`CONSULTED` `BoundMethodParameter`s, not re-resolved from the ratified ontology.
   Responses whose record ID is the `"MULTIRECORD"` or `"*"` sentinel don't correspond to a single
   real record, so `XlsxReportExporter` excludes them and `UnresolvedResponsesExporter` lists them
   in its own small workbook instead — a separate file rather than an extra sheet appended to the
   main one, since appending would mean reopening the (potentially very large) written workbook as
   a plain `XSSFWorkbook`, which for a large enough dataset can exceed Apache POI's single-zip-entry
   read cap (`RecordFormatException: ... maximum length for this record type`). Note:
   kurator-ffdq's `Issue` context class has no no-arg constructor (unlike `Measure`/`Validation`/
   `Amendment`), so RDFBeans cannot deserialize an `IssueResponse` that carries one —
   `XlsxReportExporter` leaves it unset, so ISSUE-type responses round-trip correctly but without
   per-field coloring on the Issues sheet.

`WorkbenchFacade.prepare(AppConfig)` runs ingestion → policy resolution → discovery → binding and
returns a `PreparedRun` without executing anything — this is what backs the GUI's preflight
review. `runPrepared(PreparedRun)` executes the bound tests, synthesizes `UNABLE_TO_RUN` responses
for anything policy resolution or binding couldn't resolve (so every test the use case references
appears in the final summary even if it never ran), sorts responses by phase/testId/recordId, and
exports via `ReportingService`. `run(AppConfig)` is just `runPrepared(prepare(config))`.

`ExecutionSummary` exposes filtering/counting helpers over the normalized response stream (by
phase, test type, response status, response result) — this is the plumbing layer intended for
future multi-record measure calculations; full multi-record execution is not yet implemented.

Extension points are interfaces: `IngestService`, `PolicyResolverService`, `TestDiscoveryService`,
`TestBindingService`, `ExecutionAdapter`/`TestExecutionService`, `ReportExporter`.

### Model package

`model/` holds the domain records threaded through the whole pipeline: `UseCase`, `Policy`,
`TestDefinition`, `ExecutionPlan`, `ImplementationBinding`, `BoundMethodParameter`, `Phase`,
`TestType`, `OutcomeStatus`, `Response`, `ExecutionSummary`. Most are Java records; reading their
Javadoc is often faster than reading the services that produce them.

### External test implementations

Actual BDQ test logic lives in separate FilteredPush libraries pulled in as Maven dependencies,
not in this repo: `event_date_qc`, `sci_name_qc`, `geo_ref_qc`, `rec_occur_qc` (plus
`ffdq-api`/`kurator-ffdq` for the ffdq annotation/result types). This repo is the execution
harness/GUI around them — when a discovered implementation misbehaves, the bug is more likely in
one of those upstream libraries than in the discovery/binding/execution code here.

### GUI

`BdqWorkbenchGui` is the desktop entry point (dataset file picker, use-case selection, advanced
options for custom use-case/test-definition/ontology sources, discovery packages, thread count).
`BindingReviewTableModel` backs the preflight review grid. `ExecutionProgressTracker`/
`ExecutionProgressSnapshot` back live per-phase progress and response/result counters during a
run.

## Significant work yet to be done: 

1. Workflow that allows the reduction of the input data into sets of distinct values of input information elements, test execution over those sets, and the synthesis of the results back into the original data. This reduces the number of test executions. This is a significant enhancement to the execution model and will require careful design and implementation.
2. ~~Integration of the workbench with the kurator-ffdq spreadsheet exporter~~ — done via
   `XlsxReportExporter` (see the `reporting` section above). Follow-up: the `kurator-ffdq`
   dependency in `pom.xml` is currently pinned to a `3.3.0-SNAPSHOT` build, since the "restored and
   productized" `XLSXPostProcessor` this exporter depends on only exists there; move this to a
   released `3.3.0` once kurator-ffdq cuts one. Also worth reporting upstream: kurator-ffdq's
   `Issue` context class is missing the no-arg constructor its sibling context classes
   (`Measure`/`Validation`/`Amendment`) have, which breaks RDFBeans deserialization if it's ever
   attached to a saved `IssueResponse`.

## Development

This project has used GitHub Copilot and Claude Code as AI coding assistants during development;
see the "AI-assisted development disclosure" section in README.md. All accepted AI-suggested
changes are reviewed, edited, and validated by human maintainers before merging to master —
ontology-aligned terminology and normative language are curated by the tdwg/bdq maintainers, not
by AI suggestion.

Follow the semantics of the bdqffdq.owl ontology.

## Coding style

Use tabs for indentation.  Add, and keep updated, javadoc comments on all methods and java files.

Use the following style for bracket placement and indentation of blocks:

```java
	if (condition) {
		// code block
	} else {
		// code block
	}
```

Use unix line endings.  Use spaces around operators and after commas.  Use camelCase for variable and method names, and PascalCase for class names.  Avoid deeply nested code; refactor into smaller methods if necessary.  Review and refactor code for readability and maintainability.

Block comments should be used to explain the purpose of code blocks, especially for complex logic.  Use descriptive variable and method names.  Avoid magic numbers; use constants instead.  Keep methods short and focused on a single task.  Follow the existing package structure and naming conventions.  Write unit tests for new features and bug fixes, and ensure all tests pass before committing changes.  Use version control effectively: commit often with clear messages.  Review code changes thoroughly before committing.  Document any external libraries or dependencies used in the project.  Ensure that the code is compatible with Java 17 and adheres to best practices for Java development.  Carefully avoid introducing any security vulnerabilities, especially when handling external data.  Use logging appropriately, and consistently with the included test libraries to aid in debugging and monitoring.  Keep the codebase clean and organized, removing any unused code or dependencies.  Follow the principles of object-oriented programming and design patterns where applicable.  Ensure that the application is user-friendly and provides clear feedback to users during execution.  Regularly update documentation to reflect changes in the codebase and functionality.  
