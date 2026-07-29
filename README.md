# bdq_workbench

BDQ Workbench is a Java 21 application scaffold for policy-driven Biodiversity Data Quality (BDQ) execution over Darwin Core Archives (DwC-A) and Darwin Core Data Packages.

## Build and test

```bash
./mvnw -q test
```

Run integration tests only (failsafe):

```bash
./mvnw -q verify -Prelease
```

## Run

```bash
./mvnw -q exec:java -Dexec.mainClass=org.filteredpush.bdq_workbench.app.BdqWorkbenchApplication
```

Configuration defaults are in `src/main/resources/application.properties` and can be overridden with `-Dbdq.*` system properties.

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
