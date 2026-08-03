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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
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
 * <p>Registered under format {@code "rdf"}, written as Turtle to {@code bdq-report-rdf.ttl} (see
 * {@link #fileExtension()}).
 */
public class RdfResponseExporter implements ReportExporter {
    private static final Logger LOG = LoggerFactory.getLogger(RdfResponseExporter.class);

    private static final String BDQFFDQ = "https://rs.tdwg.org/bdqffdq/terms/";
    private static final String DWC = "http://rs.tdwg.org/dwc/terms/";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String RECORD_URI_PREFIX = "urn:bdq-workbench:record:";
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
        model.setNsPrefix("rdfs", rdfs(""));
        model.setNsPrefix("xsd", XSD);

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
        for (Response response : summary.responses()) {
            Resource implementation = implementations.computeIfAbsent(
                    ImplementationKey.of(response),
                    key -> buildImplementation(model, key));
            Resource responseResource = buildResponse(model, response);
            implementation.addProperty(model.createProperty(BDQFFDQ, "producesResponse"), responseResource);
            reportInstance.addProperty(containsResponse, responseResource);
        }

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
     * applicable.
     *
     * @param model the model to add resources to
     * @param response the response to render
     * @return the new {@code Response} resource
     */
    private Resource buildResponse(Model model, Response response) {
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

        recordResourceFor(model, response.recordId())
                .ifPresent(recordResource -> resource.addProperty(model.createProperty(BDQFFDQ, "appliesTo"), recordResource));
        return resource;
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

    private void addControlledResult(Model model, Resource resource, String result, Set<String> allowed) {
        if (result != null && allowed.contains(result)) {
            resource.addProperty(model.createProperty(BDQFFDQ, "hasResponseResult"), model.createResource(BDQFFDQ + result));
        }
    }

    private String amendmentsAsJson(Map<String, String> amendments) {
        try {
            return objectMapper.writeValueAsString(amendments == null ? Map.of() : amendments);
        } catch (JsonProcessingException e) {
            LOG.warn("Unable to serialize amendments as JSON; falling back to Map#toString()", e);
            return String.valueOf(amendments);
        }
    }

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
     * {@code bdqffdq:appliesTo}.
     *
     * @param model the model to create the resource in
     * @param recordId the response's record ID
     * @return the record's resource, typed {@code dwc:Occurrence}; empty for the
     *     {@code "MULTIRECORD"} (aggregate) and {@code "*"} (synthesized unresolved/unbound
     *     placeholder) sentinel record IDs, which do not refer to a single real record
     */
    private Optional<Resource> recordResourceFor(Model model, String recordId) {
        if (recordId == null || recordId.isBlank()
                || MULTIRECORD_SENTINEL.equals(recordId) || UNRESOLVED_SENTINEL.equals(recordId)) {
            return Optional.empty();
        }
        return Optional.of(model.createResource(RECORD_URI_PREFIX + recordId)
                .addProperty(RDF.type, model.createResource(DWC + "Occurrence")));
    }

    private static String subtypeClassFor(TestType testType) {
        return switch (testType) {
            case VALIDATION -> "ValidationResponse";
            case ISSUE -> "IssueResponse";
            case MEASURE -> "MeasurementResponse";
            case AMENDMENT -> "AmendmentResponse";
            case UNKNOWN -> null;
        };
    }

    private static Set<String> allowedStatusesFor(TestType testType) {
        return testType == TestType.AMENDMENT ? AMENDMENT_STATUSES
                : testType == TestType.UNKNOWN ? ALL_KNOWN_STATUSES
                : VALIDATION_ISSUE_MEASURE_STATUSES;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(first);
        combined.addAll(second);
        return Set.copyOf(combined);
    }

    private static String rdfs(String localName) {
        return "http://www.w3.org/2000/01/rdf-schema#" + localName;
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
}
