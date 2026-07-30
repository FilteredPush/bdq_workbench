# bdq_workbench

BDQ Workbench is a Java 17 application scaffold for policy-driven Biodiversity Data Quality (BDQ) execution over Darwin Core Archives (DwC-A) and Darwin Core Data Packages.

## Build and test

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

- `https://bdq.tdwg.org/draft/dist/bdqdim.xml` (use cases)
- `https://bdq.tdwg.org/draft/dist/bdqtest.ttl` (test definitions)
- `https://bdq.tdwg.org/draft/vocabulary/bdqffdq.ttl` (ontology)

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
- `reporting`: summary output and exporter SPI (including XLS compatibility hook)

Extension points are interfaces for discovery, binding, execution adapters, and report exporters.
