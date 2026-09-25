/** RdfResponseExporter.java
 *
 * Exports an execution summary as an RDF bdqffdq:DataQualityReport (Turtle), linked by IRI
 * reference to each test's ratified bdqtest.ttl definition rather than duplicating it.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.filteredpush.bdq_workbench.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.Response;
import org.filteredpush.bdq_workbench.model.TestType;
import org.filteredpush.bdq_workbench.rdf_policy.BdqSpecificationIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports an execution summary as an RDF {@code bdqffdq:DataQualityReport}.
 *
 * <p>Mints, for the run, a {@code bdqffdq:DataQualityReport} containing every
 * {@code bdqffdq:Response} (typed {@code ValidationResponse}/{@code IssueResponse}/
 * {@code MeasurementResponse}/{@code AmendmentResponse} per {@link TestType}), each produced by a
 * {@code bdqffdq:Implementation} (one per distinct test/implementation-method binding) that
 * {@code usesSpecification} the test's {@code bdqffdq:Specification} IRI, resolved via
 * {@link BdqSpecificationIndex} from the same RDF definition files (e.g. {@code bdqtest.ttl})
 * already configured for policy resolution. This workbench does not model a test's dimension,
 * criterion, or information elements itself: a consumer that loads this output alongside those
 * same definition files gets the full chain (Response → Implementation → Specification → Method →
 * DataQualityNeed) by IRI reference, not duplication. Results are <strong>not</strong> wrapped in
 * a Web Annotation ({@code oa:Annotation}) — {@code bdqffdq:DataQualityReport} is the native,
 * unwrapped container.
 *
 * <p>Each response's record resource (the object of {@code bdqffdq:appliesTo}) is enriched with
 * the record's {@code dwc:}-prefixed term values from {@link ExecutionSummary#dataset()}, and each
 * response carries a {@code bdqwb:phase} literal (a workbench-local extension property, since
 * execution phase has no equivalent in the ratified ontology) so that a test bound in more than
 * one phase has its responses distinguishable by phase when queried. Structured detail responses
 * additionally target OA-style specific resources backed by source-location and row selectors,
 * while derived rollups are marked explicitly and linked to the detail responses and subject
 * targets that contributed to them.
 *
 * <p>Registered under format {@code "rdf"}, written as Turtle to {@code bdq-report-rdf.ttl} (see
 * {@link #fileExtension()}).
 */
public class RdfResponseExporter implements ReportExporter {
    private static final Logger LOG = LoggerFactory.getLogger(RdfResponseExporter.class);

    private static final String BDQFFDQ = "https://rs.tdwg.org/bdqffdq/terms/";
    private static final String DWC = "http://rs.tdwg.org/dwc/terms/";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String OA = "http://www.w3.org/ns/oa#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    /**
     * Namespace for workbench-local extension properties that have no equivalent in the ratified
     * bdqffdq ontology (currently just {@code phase}, distinguishing a response's execution round
     * — {@code PRE_AMENDMENT}/{@code AMENDMENT}/{@code POST_AMENDMENT} — since the same test can
     * legitimately produce responses in more than one phase).
     */
    private static final String BDQWB = "https://github.com/FilteredPush/bdq_workbench/terms/";

    private static final String RECORD_URI_PREFIX = "urn:bdq-workbench:record:";
    private static final String DWC_TERM_KEY_PREFIX = "dwc:";
    private static final String MULTIRECORD_SENTINEL = "MULTIRECORD";
    private static final String UNRESOLVED_SENTINEL = "*";

    private static final Set<String> VALIDATION_ISSUE_MEASURE_STATUSES =
            Set.of("RUN_HAS_RESULT", "INTERNAL_PREREQUISITES_NOT_MET", "EXTERNAL_PREREQUISITES_NOT_MET");
    private static final Set<String> AMENDMENT_STATUSES =
            Set.of("AMENDED", "FILLED_IN", "NOT_AMENDED", "INTERNAL_PREREQUISITES_NOT_MET", "EXTERNAL_PREREQUISITES_NOT_MET");
    private static final Set<String> ALL_KNOWN_STATUSES = union(VALIDATION_ISSUE_MEASURE_STATUSES, AMENDMENT_STATUSES);
    private static final Set<String> VALIDATION_RESULTS = Set.of("COMPLIANT", "NOT_COMPLIANT");
    private static final Set<String> ISSUE_RESULTS = Set.of("IS_ISSUE", "NOT_ISSUE", "POTENTIAL_ISSUE");
    private static final Set<String> MEASUREMENT_RESULTS = Set.of("COMPLETE", "NOT_COMPLETE");

    private final BdqSpecificationIndex specificationIndex;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates an exporter that resolves test specifications from the given RDF definition files.
     *
     * @param rdfDefinitions RDF/OWL definition files (e.g. {@code bdqtest.ttl}), typically the
     *     same {@link org.filteredpush.bdq_workbench.app.AppConfig#rdfDefinitions()} used to
     *     resolve the run's policy
     */
    public RdfResponseExporter(List<Path> rdfDefinitions) {
        this.specificationIndex = new BdqSpecificationIndex(rdfDefinitions);
    }

    /**
     * @return {@code "rdf"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "rdf";
    }

    /**
     * @return {@code "ttl"}, since this exporter writes Turtle
     */
    @Override
    public String fileExtension() {
        return "ttl";
    }

    /**
     * Builds a {@code bdqffdq:DataQualityReport} for {@code summary} and writes it as Turtle.
     *
     * @param summary the execution summary (responses and aggregated metadata) to export
     * @param outputStream the stream to write the Turtle document to; not closed by this method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("bdqffdq", BDQFFDQ);
        model.setNsPrefix("dwc", DWC);
        model.setNsPrefix("dcterms", DCTERMS);
        model.setNsPrefix("oa", OA);
        model.setNsPrefix("rdfs", rdfs(""));
        model.setNsPrefix("xsd", XSD);
        model.setNsPrefix("bdqwb", BDQWB);

        Map<String, CanonicalRecord> recordsById = summary.dataset().records().stream()
                .collect(Collectors.toMap(CanonicalRecord::id, record -> record, (left, right) -> left));

        Resource report = model.createResource(BDQFFDQ + "DataQualityReport");
        Resource reportInstance = model.createResource()
                .addProperty(RDF.type, report)
                .addLiteral(model.createProperty(DCTERMS, "created"), model.createTypedLiteral(Instant.now().toString(), XSD + "dateTime"));
        String useCaseLabel = summary.metadata() == null ? "" : summary.metadata().useCaseLabel();
        if (useCaseLabel != null && !useCaseLabel.isBlank()) {
            reportInstance.addProperty(model.createProperty(rdfs("label")), useCaseLabel);
        }

        Property containsResponse = model.createProperty(BDQFFDQ, "containsResponse");
        Map<ImplementationKey, Resource> implementations = new LinkedHashMap<>();
        Map<String, Resource> subjectTargets = new LinkedHashMap<>();
        Map<DetailResponseKey, Resource> detailResponses = new LinkedHashMap<>();
        List<DerivedResponseLinkage> derivedResponses = new ArrayList<>();
        for (Response response : summary.responses()) {
            Resource implementation = implementations.computeIfAbsent(
                    ImplementationKey.of(response),
                    key -> buildImplementation(model, key));
            Resource responseResource = buildResponse(model, response, recordsById, subjectTargets);
            implementation.addProperty(model.createProperty(BDQFFDQ, "producesResponse"), responseResource);
            reportInstance.addProperty(containsResponse, responseResource);
            if (!response.derived() && response.subjectRef() != null) {
            	detailResponses.putIfAbsent(DetailResponseKey.of(response), responseResource);
            }
            if (response.derived() && !response.contributingSubjectRefs().isEmpty()) {
            	derivedResponses.add(new DerivedResponseLinkage(response, responseResource));
            }
        }
        linkDerivedResponses(model, derivedResponses, detailResponses, recordsById, subjectTargets);

        RDFDataMgr.write(outputStream, model, Lang.TURTLE);
    }

    /**
     * Mints a {@code bdqffdq:Implementation} for one distinct test/implementation-method binding,
     * linked to its {@code bdqffdq:Specification} (when resolvable) and to a {@code bdqffdq:Mechanism}
     * identifying the implementation class/method.
     *
     * @param model the model to add resources to
     * @param key the test/implementation identity this {@code Implementation} represents
     * @return the new {@code Implementation} resource
     */
    private Resource buildImplementation(Model model, ImplementationKey key) {
        Resource implementation = model.createResource()
                .addProperty(RDF.type, model.createResource(BDQFFDQ + "Implementation"));

        specificationIndex.specificationIriFor(key.testId(), key.testType())
                .ifPresentOrElse(
                        specificationIri -> implementation.addProperty(
                                model.createProperty(BDQFFDQ, "usesSpecification"),
                                model.createResource(specificationIri)),
                        () -> LOG.debug("No bdqffdq:Specification resolved for test {}; omitting usesSpecification", key.testId()));

        String mechanismLabel = key.implementationClass() + "#" + key.implementationMethod();
        Resource mechanism = model.createResource()
                .addProperty(RDF.type, model.createResource(BDQFFDQ + "Mechanism"))
                .addProperty(model.createProperty(rdfs("label")), mechanismLabel);
        implementation.addProperty(model.createProperty(BDQFFDQ, "implementedBy"), mechanism);
        return implementation;
    }

    /**
     * Mints a {@code bdqffdq:Response} (typed by {@link TestType} when its
     * {@link Response#responseStatus()} is one of the ratified controlled-vocabulary values for
     * that type; otherwise left as a plain, untyped {@code bdqffdq:Response} carrying only its
     * comment) for one {@link Response}, with record linkage via {@code bdqffdq:appliesTo} where
     * applicable and a {@code bdqwb:phase} literal identifying its execution phase.
     *
     * @param model the model to add resources to
     * @param response the response to render
     * @param recordsById the run's input records, keyed by ID, used to enrich the response's
     *     record resource with term values
     * @param subjectTargets cache of structured subject targets already minted in the model
     * @return the new {@code Response} resource
     */
    private Resource buildResponse(
        	Model model,
        	Response response,
        	Map<String, CanonicalRecord> recordsById,
        	Map<String, Resource> subjectTargets) {
        Resource resource = model.createResource();
        String subtype = subtypeClassFor(response.testType());
        Set<String> allowedStatuses = allowedStatusesFor(response.testType());
        boolean typed = subtype != null && allowedStatuses.contains(response.responseStatus());

        resource.addProperty(RDF.type, model.createResource(BDQFFDQ + (typed ? subtype : "Response")));
        if (typed) {
            resource.addProperty(
                    model.createProperty(BDQFFDQ, "hasResponseStatus"),
                    model.createResource(BDQFFDQ + response.responseStatus()));
            addResult(model, resource, response);
        } else {
            LOG.debug(
                    "Response status {} is not a ratified value for test type {}; emitting untyped bdqffdq:Response for test {} record {}",
                    response.responseStatus(), response.testType(), response.testId(), response.recordId());
        }

        String comment = firstNonBlank(response.comment(), response.message());
        if (comment != null) {
            resource.addProperty(model.createProperty(BDQFFDQ, "hasResponseComment"), comment);
        }
        if (response.phase() != null) {
            resource.addProperty(model.createProperty(BDQWB, "phase"), response.phase().name());
        }
        resource.addLiteral(model.createProperty(BDQWB, "derived"), response.derived());
        if (response.subjectRef() != null) {
            addSubjectRefMetadata(model, resource, response.subjectRef(), "subjectRef");
            if (!StructuredSubjectSelectors.hasStructuredSelector(response.subjectRef())) {
            	resource.addProperty(
            			model.createProperty(BDQWB, "selectorDiagnostic"),
            			"Structured subject lacked unambiguous source-location and row provenance; exported at core-record granularity.");
            }
        }

        resolveTargetResource(model, response, recordsById, subjectTargets)
                .ifPresent(recordResource -> resource.addProperty(model.createProperty(BDQFFDQ, "appliesTo"), recordResource));
        return resource;
    }

    /**
     * Adds explicit rollup/detail linkage for derived core-record responses.
     *
     * @param model the model receiving linkage triples
     * @param derivedResponses derived responses collected during export
     * @param detailResponses structured detail responses keyed by record/test/phase/subject
     * @param recordsById the run's input records, keyed by ID
     * @param subjectTargets cache of structured subject targets already minted in the model
     */
    private void linkDerivedResponses(
            Model model,
            List<DerivedResponseLinkage> derivedResponses,
            Map<DetailResponseKey, Resource> detailResponses,
            Map<String, CanonicalRecord> recordsById,
            Map<String, Resource> subjectTargets) {
        Property rollsUpResponse = model.createProperty(BDQWB, "rollsUpResponse");
        Property contributingSubject = model.createProperty(BDQWB, "contributingSubject");
        for (DerivedResponseLinkage linkage : derivedResponses) {
            Response response = linkage.response();
            for (org.filteredpush.bdq_workbench.model.SubjectRef subjectRef : response.contributingSubjectRefs()) {
                Resource detailResource = detailResponses.get(new DetailResponseKey(
                		response.recordId(),
                		response.testId(),
                		response.phase(),
                		subjectRef.sortKey()));
                if (detailResource != null) {
                	linkage.resource().addProperty(rollsUpResponse, detailResource);
                }
                structuredTargetFor(model, subjectRef.coreRecordId(), subjectRef, recordsById, subjectTargets)
                		.ifPresent(target -> linkage.resource().addProperty(contributingSubject, target));
            }
        }
    }

    /**
     * Resolves the resource that a response applies to, preferring a structured subject target when
     * unambiguous provenance is available and otherwise degrading to the core record resource.
     *
     * @param model the model to create resources in
     * @param response the response being exported
     * @param recordsById the run's input records, keyed by ID
     * @param subjectTargets cache of structured subject targets already minted in the model
     * @return the response target resource, if any
     */
    private Optional<Resource> resolveTargetResource(
            Model model,
            Response response,
            Map<String, CanonicalRecord> recordsById,
            Map<String, Resource> subjectTargets) {
        if (response.subjectRef() != null) {
            Optional<Resource> structuredTarget = structuredTargetFor(
                	model, response.recordId(), response.subjectRef(), recordsById, subjectTargets);
            if (structuredTarget.isPresent()) {
                return structuredTarget;
            }
        }
        return recordResourceFor(model, response.recordId(), recordsById);
    }

    /**
     * Resolves or mints a structured target resource for one subject reference.
     *
     * @param model the model to create resources in
     * @param recordId the core record ID owning the subject
     * @param subjectRef the structured subject reference to render
     * @param recordsById the run's input records, keyed by ID
     * @param subjectTargets cache of structured subject targets already minted in the model
     * @return the target resource, or empty when the subject lacks unambiguous selector provenance
     */
    private Optional<Resource> structuredTargetFor(
            Model model,
            String recordId,
            org.filteredpush.bdq_workbench.model.SubjectRef subjectRef,
            Map<String, CanonicalRecord> recordsById,
            Map<String, Resource> subjectTargets) {
        if (!StructuredSubjectSelectors.hasStructuredSelector(subjectRef)) {
            return Optional.empty();
        }
        String ownerRecordId = StructuredSubjectSelectors.ownerRecordId(recordId, subjectRef);
        return Optional.of(subjectTargets.computeIfAbsent(structuredTargetCacheKey(ownerRecordId, subjectRef), ignored -> {
            Resource target = model.createResource()
                	.addProperty(RDF.type, model.createResource(OA + "SpecificResource"))
                	.addProperty(RDF.type, model.createResource(BDQWB + "StructuredRecordTarget"));
            addSubjectRefMetadata(model, target, subjectRef, "targetSubjectRef");
            recordResourceFor(model, ownerRecordId, recordsById)
                	.ifPresent(recordResource -> target.addProperty(model.createProperty(DCTERMS, "isPartOf"), recordResource));

            Resource source = model.createResource()
                	.addProperty(model.createProperty(rdfs("label")), subjectRef.sourceLocation());
            target.addProperty(model.createProperty(OA, "hasSource"), source);
            target.addProperty(model.createProperty(OA, "hasSelector"), buildRowSelector(model, subjectRef));
            return target;
        }));
    }

    /**
     * Adds workbench-local metadata describing a structured subject reference.
     *
     * @param model the model receiving metadata
     * @param resource the resource to annotate
     * @param subjectRef the subject reference to describe
     * @param prefix the local-name prefix for the emitted properties
     */
    private void addSubjectRefMetadata(
            Model model,
            Resource resource,
            org.filteredpush.bdq_workbench.model.SubjectRef subjectRef,
            String prefix) {
        resource.addProperty(model.createProperty(BDQWB, prefix + "SortKey"), subjectRef.sortKey());
        addIfPresent(resource, model.createProperty(BDQWB, prefix + "CoreRecordId"), subjectRef.coreRecordId());
        addIfPresent(resource, model.createProperty(BDQWB, prefix + "RelationName"), subjectRef.relationName());
        addIfPresent(resource, model.createProperty(BDQWB, prefix + "SourceTable"), subjectRef.sourceTable());
        addIfPresent(resource, model.createProperty(BDQWB, prefix + "SourceLocation"), subjectRef.sourceLocation());
        addIfPresent(resource, model.createProperty(BDQWB, prefix + "RowRef"), subjectRef.rowRef());
    }

    /**
     * Builds a W3C-Annotation-style row selector from a subject reference.
     *
     * @param model the model to create resources in
     * @param subjectRef the subject reference whose selector should be rendered
     * @return a selector resource naming the row or row-ref within the source file
     */
    private Resource buildRowSelector(Model model, org.filteredpush.bdq_workbench.model.SubjectRef subjectRef) {
        Resource selector = model.createResource()
                .addProperty(RDF.type, model.createResource(OA + "FragmentSelector"))
                .addProperty(RDF.value, StructuredSubjectSelectors.selectorValue(subjectRef.rowRef()));
        addIfPresent(selector, model.createProperty(BDQWB, "rowRef"), subjectRef.rowRef());
        return selector;
    }

    /**
     * Builds a cache key for one record-scoped structured target.
     *
     * @param recordId the owning core record ID when known
     * @param subjectRef the subject reference being rendered
     * @return the cache key for that record-scoped structured target
     */
    private static String structuredTargetCacheKey(
        	String recordId,
        	org.filteredpush.bdq_workbench.model.SubjectRef subjectRef) {
        String owner = recordId == null || recordId.isBlank() ? subjectRef.coreRecordId() : recordId;
        return (owner == null ? "" : owner) + "\u0002" + subjectRef.sortKey();
    }

    /**
     * Adds {@code hasResponseResult}/{@code hasResponseResultValue} to a typed response resource,
     * per the type-specific rules (Amendment responses never use {@code hasResponseResult};
     * Measurement responses use the object property only for {@code COMPLETE}/{@code NOT_COMPLETE},
     * falling back to a numeric {@code hasResponseResultValue} literal).
     *
     * @param model the model to add statements to
     * @param resource the response resource being built
     * @param response the response being rendered
     */
    private void addResult(Model model, Resource resource, Response response) {
        String result = response.responseResult();
        switch (response.testType()) {
            case VALIDATION -> addControlledResult(model, resource, result, VALIDATION_RESULTS);
            case ISSUE -> addControlledResult(model, resource, result, ISSUE_RESULTS);
            case MEASURE -> {
                if (result != null && MEASUREMENT_RESULTS.contains(result)) {
                    resource.addProperty(model.createProperty(BDQFFDQ, "hasResponseResult"), model.createResource(BDQFFDQ + result));
                } else {
                    tryParseNumeric(result).ifPresent(value -> resource.addProperty(
                            model.createProperty(BDQFFDQ, "hasResponseResultValue"), model.createTypedLiteral(value)));
                }
            }
            case AMENDMENT -> resource.addProperty(
                    model.createProperty(BDQFFDQ, "hasResponseResultValue"),
                    amendmentsAsJson(response.amendments()));
            default -> {
                // UNKNOWN test type with an otherwise-recognized status: no controlled result vocabulary applies.
            }
        }
    }

    /**
     * Adds a controlled-vocabulary response result when one is present and allowed.
     *
     * @param model the model receiving statements
     * @param resource the response resource being built
     * @param result the response result value to check
     * @param allowed the allowed controlled-vocabulary result values for the response type
     */
    private void addControlledResult(Model model, Resource resource, String result, Set<String> allowed) {
        if (result != null && allowed.contains(result)) {
            resource.addProperty(model.createProperty(BDQFFDQ, "hasResponseResult"), model.createResource(BDQFFDQ + result));
        }
    }

    /**
     * Serializes amendment outputs to a stable JSON string for RDF export.
     *
     * @param amendments the amendment map to serialize
     * @return the serialized amendment map
     */
    private String amendmentsAsJson(Map<String, String> amendments) {
        try {
            return objectMapper.writeValueAsString(amendments == null ? Map.of() : amendments);
        } catch (JsonProcessingException e) {
            LOG.warn("Unable to serialize amendments as JSON; falling back to Map#toString()", e);
            return String.valueOf(amendments);
        }
    }

    /**
     * Parses a measurement result as a numeric literal when possible.
     *
     * @param value the raw result value
     * @return the parsed numeric value, or empty when the value is absent or non-numeric
     */
    private static Optional<Double> tryParseNumeric(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a stable, synthetic IRI for a record ID, for use as the object of
     * {@code bdqffdq:appliesTo}, enriched with the record's {@code dwc:}-prefixed term values
     * when the record is found in {@code recordsById}.
     *
     * <p>Term values reflect the record's state at export time: for a {@code POST_AMENDMENT}
     * response this is the correct (amended) value examined, but for an earlier-phase response
     * whose term was later amended, it will show the final rather than the original value — a
     * known, accepted limitation rather than something this exporter attempts to reconstruct.
     *
     * @param model the model to create the resource in
     * @param recordId the response's record ID
     * @param recordsById the run's input records, keyed by ID
     * @return the record's resource, typed {@code dwc:Occurrence}; empty for the
     *     {@code "MULTIRECORD"} (aggregate) and {@code "*"} (synthesized unresolved/unbound
     *     placeholder) sentinel record IDs, which do not refer to a single real record
     */
    private Optional<Resource> recordResourceFor(Model model, String recordId, Map<String, CanonicalRecord> recordsById) {
        if (recordId == null || recordId.isBlank()
                || MULTIRECORD_SENTINEL.equals(recordId) || UNRESOLVED_SENTINEL.equals(recordId)) {
            return Optional.empty();
        }
        Resource recordResource = model.createResource(RECORD_URI_PREFIX + recordId)
                .addProperty(RDF.type, model.createResource(DWC + "Occurrence"));
        CanonicalRecord record = recordsById.get(recordId);
        if (record != null) {
            record.terms().forEach((term, value) -> {
                if (term != null && term.startsWith(DWC_TERM_KEY_PREFIX) && value != null && !value.isBlank()) {
                    recordResource.addProperty(model.createProperty(DWC, term.substring(DWC_TERM_KEY_PREFIX.length())), value);
                }
            });
        }
        return Optional.of(recordResource);
    }

    /**
     * Resolves the bdqffdq response subclass corresponding to a test type.
     *
     * @param testType the test type to map
     * @return the bdqffdq response subclass local name, or {@code null} for unknown types
     */
    private static String subtypeClassFor(TestType testType) {
        return switch (testType) {
            case VALIDATION -> "ValidationResponse";
            case ISSUE -> "IssueResponse";
            case MEASURE -> "MeasurementResponse";
            case AMENDMENT -> "AmendmentResponse";
            case UNKNOWN -> null;
        };
    }

    /**
     * Resolves the controlled-vocabulary response statuses valid for a given test type.
     *
     * @param testType the test type to map
     * @return the allowed response statuses for that type
     */
    private static Set<String> allowedStatusesFor(TestType testType) {
        return testType == TestType.AMENDMENT ? AMENDMENT_STATUSES
                : testType == TestType.UNKNOWN ? ALL_KNOWN_STATUSES
                : VALIDATION_ISSUE_MEASURE_STATUSES;
    }

    /**
     * Returns the first non-blank string from two candidates.
     *
     * @param first the first candidate
     * @param second the fallback candidate
     * @return the first non-blank candidate, or {@code null} when both are blank
     */
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    /**
     * Returns the union of two sets while preserving first-seen encounter order.
     *
     * @param first the first set
     * @param second the second set
     * @return the ordered union of both sets
     */
    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(first);
        combined.addAll(second);
        return Set.copyOf(combined);
    }

    /**
     * Resolves one RDF Schema namespace IRI.
     *
     * @param localName the local name to append to the RDF Schema namespace
     * @return the full RDF Schema IRI
     */
    private static String rdfs(String localName) {
        return "http://www.w3.org/2000/01/rdf-schema#" + localName;
    }

    /**
     * Adds a literal property only when its value is present.
     *
     * @param resource the resource to add the property to
     * @param property the property to add
     * @param value the literal value to add when non-blank
     */
    private static void addIfPresent(Resource resource, Property property, String value) {
        if (value != null && !value.isBlank()) {
        	resource.addProperty(property, value);
        }
    }

    /**
     * Identity of one distinct test/implementation-method binding, used to mint exactly one
     * {@code bdqffdq:Implementation} shared by every per-record {@link Response} for that binding.
     *
     * @param testId the test's IRI
     * @param testType the test's type, used to resolve the correct {@code bdqffdq:*Method} kind
     * @param implementationClass the bound implementation's fully-qualified class name
     * @param implementationMethod the bound implementation's method name
     */
    private record ImplementationKey(String testId, TestType testType, String implementationClass, String implementationMethod) {
        private static ImplementationKey of(Response response) {
            return new ImplementationKey(
                    response.testId(), response.testType(), response.implementationClass(), response.implementationMethod());
        }
    }

    /**
     * Identity of one structured detail response for rollup linkage.
     *
     * @param recordId the core record ID
     * @param testId the test identifier
     * @param phase the execution phase
     * @param subjectSortKey the contributing subject's stable sort key
     */
    private record DetailResponseKey(String recordId, String testId, org.filteredpush.bdq_workbench.model.Phase phase, String subjectSortKey) {
        private static DetailResponseKey of(Response response) {
            return new DetailResponseKey(
                    response.recordId(),
                    response.testId(),
                    response.phase(),
                    response.subjectRef().sortKey());
        }
    }

    /**
     * Pair of a derived response and its exported RDF resource for a second-pass linkage step.
     *
     * @param response the derived response
     * @param resource the exported RDF resource representing it
     */
    private record DerivedResponseLinkage(Response response, Resource resource) {
    }
}
