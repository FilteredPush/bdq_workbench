/** XlsxReportExporter.java
 *
 * Exports an execution summary as an XLSX spreadsheet using kurator-ffdq's XLSXPostProcessor.
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.datakurator.dwcloud.Vocabulary;
import org.datakurator.ffdq.model.DataResource;
import org.datakurator.ffdq.model.Entity;
import org.datakurator.ffdq.model.InformationElement;
import org.datakurator.ffdq.model.ResultState;
import org.datakurator.ffdq.model.Specification;
import org.datakurator.ffdq.model.context.Amendment;
import org.datakurator.ffdq.model.context.Measure;
import org.datakurator.ffdq.model.context.Validation;
import org.datakurator.ffdq.model.report.AmendmentResponse;
import org.datakurator.ffdq.model.report.IssueResponse;
import org.datakurator.ffdq.model.report.MeasureResponse;
import org.datakurator.ffdq.model.report.Result;
import org.datakurator.ffdq.model.report.ValidationResponse;
import org.datakurator.ffdq.rdf.FFDQModel;
import org.datakurator.postprocess.XLSXPostProcessor;
import org.filteredpush.bdq_workbench.model.BoundMethodParameter;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.ParameterRole;
import org.filteredpush.bdq_workbench.model.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports an execution summary as an XLSX spreadsheet via kurator-ffdq's {@link XLSXPostProcessor}.
 *
 * <p>Builds an in-memory kurator-ffdq {@link FFDQModel} directly from the run's {@link
 * ExecutionSummary} (one {@link DataResource} per input record, one {@code bdqffdq:*Response} per
 * {@link Response}) rather than round-tripping through {@link RdfResponseExporter}'s Turtle output
 * — that output deliberately omits the dimension/criterion/information-element chain this
 * exporter needs for per-field cell coloring, and uses a different RDF framework (Jena) than
 * kurator-ffdq's RDF4J/RDFBeans-backed model.
 *
 * <p>Each test's Darwin Core information elements (the fields it acts upon or consults, used both
 * for cell coloring and for padding every record with empty-valued columns for fields the use
 * case's tests expect but the input data lacks) are derived from the run's {@link
 * ImplementationBinding}s rather than re-resolved from the ratified ontology: {@link
 * BoundMethodParameter}s with role {@link ParameterRole#ACTED_UPON}/{@link
 * ParameterRole#CONSULTED} already carry the term name, populated during binding regardless of
 * whether the term was actually present in the input data.
 *
 * <p>Responses whose record ID is one of the sentinel values {@code "MULTIRECORD"} (built-in
 * multi-record measures) or {@code "*"} (synthesized unresolved/unbound placeholders) don't
 * correspond to a single real record, so {@link XLSXPostProcessor} has no place for them; they are
 * excluded here and instead listed in {@link UnresolvedResponsesExporter}'s own small workbook.
 * Keeping them out of this file (rather than appending a sheet to it) avoids ever having to read
 * this workbook back into memory as a plain {@link org.apache.poi.xssf.usermodel.XSSFWorkbook} —
 * {@link XLSXPostProcessor} streams its output directly to {@code outputStream} via {@code
 * SXSSFWorkbook}, and reopening a large one to append a sheet has previously hit Apache POI's
 * ~100MB single-zip-entry read cap on real, large datasets.
 *
 * <p>ISSUE-type responses are a known exception to the per-field coloring above: kurator-ffdq's
 * {@code Issue} context class (unlike {@code Measure}/{@code Validation}/{@code Amendment}) has no
 * no-arg constructor, so RDFBeans cannot deserialize an {@code IssueResponse} that carries one —
 * the whole response would silently fail to round-trip through {@link XLSXPostProcessor}'s
 * re-query of the model. This exporter leaves {@code issueInContext} unset entirely so the
 * response itself is preserved; the Issues sheet still gets its row and every field column, just
 * without acted-upon/consulted coloring.
 *
 * <p>Registered under format {@code "xls"}, written as an Office Open XML workbook to
 * {@code bdq-report-xls.xlsx} (see {@link #fileExtension()}).
 */
public class XlsxReportExporter implements ReportExporter {
    private static final Logger LOG = LoggerFactory.getLogger(XlsxReportExporter.class);

    private static final String DWC_NAMESPACE = "http://rs.tdwg.org/dwc/terms/";
    private static final String DWC_TERM_KEY_PREFIX = "dwc:";
    private static final Set<String> SENTINEL_RECORD_IDS = Set.of("MULTIRECORD", "*");

    /**
     * @return {@code "xls"}, the format identifier for this exporter
     */
    @Override
    public String format() {
        return "xls";
    }

    /**
     * @return {@code "xlsx"}, since this exporter writes an Office Open XML workbook
     */
    @Override
    public String fileExtension() {
        return "xlsx";
    }

    /**
     * Builds an in-memory {@link FFDQModel} for {@code summary} and streams it to an XLSX
     * workbook directly to {@code outputStream} via {@link XLSXPostProcessor}. Responses that
     * don't apply to a single real record are excluded (see {@link UnresolvedResponsesExporter}).
     *
     * @param summary the execution summary (responses, dataset, and bindings) to export
     * @param outputStream the stream to write the XLSX workbook to; not closed by this method
     * @throws IOException if writing to {@code outputStream} fails
     */
    @Override
    public void export(ExecutionSummary summary, OutputStream outputStream) throws IOException {
        FFDQModel model = new FFDQModel();
        Vocabulary vocab = model.getVocab();

        Map<String, List<String>> fieldsByTestId = fieldsExpectedByTest(summary.bindings());
        Set<String> allExpectedFields = new LinkedHashSet<>();
        fieldsByTestId.values().forEach(allExpectedFields::addAll);

        Map<String, DataResource> dataResourcesByRecordId = buildDataResources(
                model, vocab, summary.dataset().records(), allExpectedFields);

        boolean wroteAnyResponse = false;
        for (Response response : summary.responses()) {
            if (SENTINEL_RECORD_IDS.contains(response.recordId())) {
                continue;
            }
            DataResource dataResource = dataResourcesByRecordId.get(response.recordId());
            if (dataResource == null) {
                LOG.warn("No input record found for record id {}; skipping response for test {}",
                        response.recordId(), response.testId());
                continue;
            }
            addResponse(model, dataResource, response, fieldsByTestId.getOrDefault(response.testId(), List.of()));
            wroteAnyResponse = true;
        }

        if (wroteAnyResponse) {
            new XLSXPostProcessor(model).postprocess(outputStream);
        } else {
            writeEmptyWorkbook(outputStream);
        }
    }

    /**
     * Derives, for each test ID with a binding in the run, the list of Darwin Core term names the
     * bound implementation acts upon or consults.
     *
     * <p>Terms come from every {@link BoundMethodParameter} with role {@link
     * ParameterRole#ACTED_UPON} or {@link ParameterRole#CONSULTED}, whether or not the term was
     * actually bound (a term missing from the input data still appears here, with {@link
     * BoundMethodParameter#resolvedSource()} carrying the raw {@code dwc:}-prefixed annotation
     * value rather than a matched field name).
     *
     * @param bindings the run's test/implementation bindings
     * @return term names per test ID, in encounter order; tests with no acted-upon/consulted
     *     parameters are omitted
     */
    private Map<String, List<String>> fieldsExpectedByTest(List<ImplementationBinding> bindings) {
        Map<String, List<String>> fieldsByTestId = new LinkedHashMap<>();
        for (ImplementationBinding binding : bindings) {
            Set<String> fields = new LinkedHashSet<>();
            for (BoundMethodParameter bound : binding.parameterBindings()) {
                ParameterRole role = bound.parameter().role();
                if (role != ParameterRole.ACTED_UPON && role != ParameterRole.CONSULTED) {
                    continue;
                }
                String term = bound.resolvedSource();
                if (term == null || term.isBlank()) {
                    continue;
                }
                fields.add(stripDwcPrefix(term));
            }
            if (!fields.isEmpty()) {
                fieldsByTestId.put(binding.testId(), List.copyOf(fields));
            }
        }
        return fieldsByTestId;
    }

    /**
     * Builds one {@link DataResource} per input record, padding each record's term map with an
     * empty value for every field in {@code allExpectedFields} that the record doesn't already
     * have — so a term a use case's tests expect as an input information element, but which the
     * input data lacks entirely, still appears as a (blank) column in the report, exactly as if
     * present with an empty value.
     *
     * @param model the model to load each resource's underlying RDF into
     * @param vocab the Darwin Core vocabulary used to resolve term names to predicate IRIs
     * @param records the input dataset's records
     * @param allExpectedFields the union of Darwin Core terms every bound test in the run acts
     *     upon or consults, including ones absent from the input data
     * @return the created data resources, keyed by record ID
     */
    private Map<String, DataResource> buildDataResources(
            FFDQModel model, Vocabulary vocab, List<CanonicalRecord> records, Set<String> allExpectedFields) {
        String idTerm = vocab.getIdTerm();
        Map<String, DataResource> dataResourcesByRecordId = new LinkedHashMap<>();
        for (CanonicalRecord record : records) {
            Map<String, String> terms = new LinkedHashMap<>();
            record.terms().forEach((term, value) -> terms.put(stripDwcPrefix(term), value));
            for (String field : allExpectedFields) {
                terms.putIfAbsent(field, "");
            }
            terms.putIfAbsent(idTerm, record.id());
            DataResource dataResource = new DataResource(vocab, terms);
            model.load(dataResource.asModel());
            dataResourcesByRecordId.put(record.id(), dataResource);
        }
        return dataResourcesByRecordId;
    }

    /**
     * Strips a leading {@code "dwc:"} prefix from a term key, if present, so that terms are
     * looked up and padded consistently whether the input record's term map keys are bare local
     * names (as produced by {@code DwcArchiveIngestor}/{@code DataPackageIngestor}) or {@code
     * dwc:}-prefixed.
     *
     * @param term the term name, possibly {@code dwc:}-prefixed
     * @return the bare local term name
     */
    private static String stripDwcPrefix(String term) {
        return term != null && term.startsWith(DWC_TERM_KEY_PREFIX)
                ? term.substring(DWC_TERM_KEY_PREFIX.length())
                : term;
    }

    /**
     * Builds and saves the kurator-ffdq response object (and its result, entity, and context)
     * matching {@code response}'s test type.
     *
     * @param model the model to save the constructed objects into
     * @param dataResource the record's data resource, linked via {@code bdqffdq:appliesTo}
     * @param response the response to render
     * @param fields the Darwin Core terms this test acts upon or consults, used for cell coloring
     */
    private void addResponse(FFDQModel model, DataResource dataResource, Response response, List<String> fields) {
        ResultState state = new ResultState(defaulted(response.responseStatus()));
        model.save(state);

        Specification specification = new Specification();
        specification.setLabel(response.testId());
        model.save(specification);

        InformationElement informationElement = fields.isEmpty() ? null : new InformationElement(fieldUris(fields));
        if (informationElement != null) {
            model.save(informationElement);
        }

        switch (response.testType()) {
            case VALIDATION -> {
                Result result = buildResult(model, state, response.responseResult(), response.comment());
                Validation criterion = null;
                if (informationElement != null) {
                    criterion = new Validation();
                    criterion.setInformationElements(informationElement);
                    model.save(criterion);
                }
                ValidationResponse validationResponse = new ValidationResponse();
                validationResponse.setDataResource(dataResource.getURI());
                validationResponse.setSpecification(specification);
                validationResponse.setResult(result);
                validationResponse.setCriterion(criterion);
                model.save(validationResponse);
            }
            case MEASURE -> {
                Result result = buildResult(model, state, response.responseResult(), response.comment());
                Measure dimension = null;
                if (informationElement != null) {
                    dimension = new Measure();
                    dimension.setInformationElements(informationElement);
                    model.save(dimension);
                }
                MeasureResponse measureResponse = new MeasureResponse();
                measureResponse.setDataResource(dataResource.getURI());
                measureResponse.setSpecification(specification);
                measureResponse.setResult(result);
                measureResponse.setDimension(dimension);
                model.save(measureResponse);
            }
            case ISSUE -> {
                // kurator-ffdq's Issue context class (unlike Measure/Validation/Amendment) has no
                // no-arg constructor, so RDFBeans cannot deserialize an IssueResponse that carries
                // one — attaching it here would silently drop the whole response when
                // XLSXPostProcessor re-reads it from the model. Leave issueInContext unset: the
                // Issues sheet still gets its row and every field column, just without per-cell
                // acted-upon/consulted coloring for this test type.
                Result result = buildResult(model, state, response.responseResult(), response.comment());
                IssueResponse issueResponse = new IssueResponse();
                issueResponse.setDataResource(dataResource.getURI());
                issueResponse.setSpecification(specification);
                issueResponse.setResult(result);
                model.save(issueResponse);
            }
            case AMENDMENT -> {
                Result result = buildAmendmentResult(model, dataResource, state, response);
                Amendment enhancement = null;
                if (informationElement != null) {
                    enhancement = new Amendment();
                    enhancement.setInformationElements(informationElement);
                    model.save(enhancement);
                }
                AmendmentResponse amendmentResponse = new AmendmentResponse();
                amendmentResponse.setDataResource(dataResource.getURI());
                amendmentResponse.setSpecification(specification);
                amendmentResponse.setResult(result);
                amendmentResponse.setEnhancement(enhancement);
                model.save(amendmentResponse);
            }
            case UNKNOWN -> LOG.debug("Skipping response with UNKNOWN test type for test {}", response.testId());
        }
    }

    /**
     * Builds a {@link Result} carrying a plain string-valued {@link Entity} (used for
     * VALIDATION/MEASURE/ISSUE responses, whose result is a controlled-vocabulary label or a
     * numeric measure value rendered as a string).
     *
     * @param model the model to save the result and entity into
     * @param state the result's state
     * @param value the response's result value; no entity is attached if blank
     * @param comment the response's comment
     * @return the saved result
     */
    private Result buildResult(FFDQModel model, ResultState state, String value, String comment) {
        Result result = new Result();
        result.setState(state);
        if (value != null && !value.isBlank()) {
            Entity entity = new Entity();
            entity.setValue(value);
            model.save(entity);
            result.setEntity(entity);
        }
        result.setComment(comment);
        model.save(result);
        return result;
    }

    /**
     * Builds the {@link Result} for an AMENDMENT response. When the response changed any values,
     * a synthetic "amended" {@link DataResource} is created holding only the changed term/value
     * pairs and linked via a {@link URI}-valued {@link Entity}, matching the shape {@link
     * XLSXPostProcessor#initAmendmentsSheet} expects to look up post-amendment values. When
     * nothing changed, the result carries no entity, exactly as kurator-ffdq's own tests model a
     * {@code NOT_AMENDED} outcome.
     *
     * @param model the model to save the result, entity, and (if needed) amended resource into
     * @param dataResource the record's original data resource
     * @param state the result's state
     * @param response the amendment response being rendered
     * @return the saved result
     */
    private Result buildAmendmentResult(FFDQModel model, DataResource dataResource, ResultState state, Response response) {
        Result result = new Result();
        result.setState(state);
        result.setComment(response.comment());
		if (response.amendments() != null && !response.amendments().isEmpty()) {
			Map<String, String> normalizedAmendments = new LinkedHashMap<>();
			response.amendments().forEach((term, value) -> normalizedAmendments.put(stripDwcPrefix(term), value == null ? "" : value));
			DataResource amendedResource = new DataResource(model.getVocab(), normalizedAmendments);
            Entity entity = new Entity();
            entity.setValue(amendedResource.getURI());
            model.save(entity);
            result.setEntity(entity);
        }
        model.save(result);
        return result;
    }

    /**
     * Resolves Darwin Core term names to information-element URIs for {@link
     * XLSXPostProcessor}'s {@code localNameFromUri} extraction (the last path segment), using the
     * plain {@code http://rs.tdwg.org/dwc/terms/} namespace directly rather than the bundled
     * dwcloud vocabulary, since only the local name is ever read back.
     *
     * @param fields the Darwin Core term names to resolve
     * @return one URI per field, in the same order
     */
    private List<URI> fieldUris(List<String> fields) {
        List<URI> uris = new ArrayList<>(fields.size());
        for (String field : fields) {
            uris.add(URI.create(DWC_NAMESPACE + field));
        }
        return uris;
    }

    /**
     * Writes a minimal single-sheet workbook noting that the run produced no per-record
     * responses, so {@link XLSXPostProcessor} (which requires at least one) is not invoked.
     *
     * @param outputStream the stream to write the placeholder workbook to
     * @throws IOException if writing fails
     */
    private void writeEmptyWorkbook(OutputStream outputStream) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Summary");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(
                    "This run produced no responses applicable to a single record; see "
                            + "bdq-report-xls-unresolved.xlsx, if present, for details.");
            workbook.write(outputStream);
        }
    }

    private static String defaulted(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
