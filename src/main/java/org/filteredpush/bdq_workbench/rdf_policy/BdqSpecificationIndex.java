/** BdqSpecificationIndex.java
 *
 * Resolves a BDQ test's IRI to the bdqffdq:Specification IRI its ratified bdqtest.ttl definition
 * links to, so RDF output can reference that definition instead of duplicating it.
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
package org.filteredpush.bdq_workbench.rdf_policy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.filteredpush.bdq_workbench.model.TestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a test IRI to the {@code bdqffdq:Specification} IRI its ratified test definition
 * links to, by walking the {@code bdqffdq:Method} layer of the same kind of RDF definition files
 * (e.g. {@code bdqtest.ttl}) already loaded elsewhere via {@link RdfDefinitionsLoader}.
 *
 * <p>The ratified TDWG {@code bdqtest.ttl} already fully specifies each test's dimension,
 * criterion/enhancement, and acted-upon/consulted information elements — data this workbench does
 * not (and does not need to) capture itself. Rather than re-deriving or duplicating that data as
 * Java model classes, this index resolves only the one link a report exporter needs: given a
 * test's IRI, find the {@code bdqffdq:ValidationMethod}/{@code IssueMethod}/{@code MeasurementMethod}/
 * {@code AmendmentMethod} whose {@code forValidation}/{@code forIssue}/{@code forMeasure}/
 * {@code forAmendment} property points at that test (directly, or at a
 * {@code dcterms:isVersionOf}-related variant of it), and return the {@code bdqffdq:Specification}
 * IRI that method's {@code hasSpecification} property points at. A consumer that loads the
 * exporter's output alongside {@code bdqtest.ttl} can then follow that IRI to the full
 * {@code Specification}/{@code Method}/{@code DataQualityNeed} chain (dimension, criterion,
 * information elements, ...) without this workbench ever having to model it.
 */
public class BdqSpecificationIndex {
    private static final Logger LOG = LoggerFactory.getLogger(BdqSpecificationIndex.class);
    private static final String BDQFFDQ_NS = "https://rs.tdwg.org/bdqffdq/terms/";
    private static final String DCTERMS_IS_VERSION_OF = "http://purl.org/dc/terms/isVersionOf";
    private static final String HAS_SPECIFICATION = BDQFFDQ_NS + "hasSpecification";

    private final Model model;

    /**
     * Creates an index over the given RDF definition files, loaded via
     * {@link RdfDefinitionsLoader#load(List)}.
     *
     * @param rdfDefinitions RDF/OWL definition files (e.g. {@code bdqtest.ttl}) to load; typically
     *     the same {@link org.filteredpush.bdq_workbench.app.AppConfig#rdfDefinitions()} passed to
     *     {@link RdfPolicyResolverService}
     */
    public BdqSpecificationIndex(List<Path> rdfDefinitions) {
        this.model = RdfDefinitionsLoader.load(rdfDefinitions);
    }

    /**
     * Resolves a test's {@code bdqffdq:Specification} IRI.
     *
     * @param testId the test's IRI, as found on
     *     {@link org.filteredpush.bdq_workbench.model.Response#testId()}
     * @param testType the test's type, used to pick which {@code for*} method-linking property to
     *     look for ({@code UNKNOWN} tries all four)
     * @return the {@code bdqffdq:Specification} IRI the test's ratified definition links to, or
     *     empty if {@code testId} is not a resource known to the loaded RDF definitions (common for
     *     locally-authored, non-standard tests)
     */
    public Optional<String> specificationIriFor(String testId, TestType testType) {
        if (testId == null || testId.isBlank()) {
            return Optional.empty();
        }
        Resource testResource = model.getResource(testId);
        List<Resource> candidateTestResources = withVersionVariants(testResource);
        for (Property forProperty : forProperties(testType)) {
            for (Resource candidate : candidateTestResources) {
                StmtIterator methods = model.listStatements(null, forProperty, candidate);
                if (methods.hasNext()) {
                    Resource method = methods.nextStatement().getSubject();
                    Optional<String> specification = specificationOf(method);
                    if (specification.isPresent()) {
                        return specification;
                    }
                }
            }
        }
        LOG.debug("No bdqffdq:Specification found in loaded RDF definitions for test {}", testId);
        return Optional.empty();
    }

    /**
     * Collects {@code resource} together with any resource it is {@code dcterms:isVersionOf}, and
     * any resource that is {@code dcterms:isVersionOf} it — covering both directions of the
     * bare-test/dated-version relationship {@code bdqtest.ttl} uses.
     *
     * @param resource the resource to expand; may have no version relationships
     * @return {@code resource} and its version-related variants
     */
    private List<Resource> withVersionVariants(Resource resource) {
        Property isVersionOf = model.createProperty(DCTERMS_IS_VERSION_OF);
        List<Resource> variants = new ArrayList<>();
        variants.add(resource);
        StmtIterator versionOf = resource.listProperties(isVersionOf);
        while (versionOf.hasNext()) {
            RDFNode object = versionOf.nextStatement().getObject();
            if (object.isResource()) {
                variants.add(object.asResource());
            }
        }
        StmtIterator versionedBy = model.listStatements(null, isVersionOf, resource);
        while (versionedBy.hasNext()) {
            variants.add(versionedBy.nextStatement().getSubject());
        }
        return variants;
    }

    /**
     * Reads a method resource's {@code bdqffdq:hasSpecification} object as a specification IRI.
     *
     * @param method the {@code bdqffdq:*Method} resource
     * @return the specification IRI, or empty if the method has no (resource-valued)
     *     {@code hasSpecification} statement
     */
    private Optional<String> specificationOf(Resource method) {
        StmtIterator specifications = method.listProperties(model.createProperty(HAS_SPECIFICATION));
        while (specifications.hasNext()) {
            RDFNode object = specifications.nextStatement().getObject();
            if (object.isResource() && object.asResource().getURI() != null) {
                return Optional.of(object.asResource().getURI());
            }
        }
        return Optional.empty();
    }

    /**
     * Picks which {@code bdqffdq:for*} method-linking properties to search, based on test type.
     *
     * @param testType the test's type; {@code null} or {@code UNKNOWN} tries all four
     * @return the candidate properties to search, in preference order
     */
    private List<Property> forProperties(TestType testType) {
        if (testType == null) {
            return allForProperties();
        }
        return switch (testType) {
            case VALIDATION -> List.of(model.createProperty(BDQFFDQ_NS + "forValidation"));
            case ISSUE -> List.of(model.createProperty(BDQFFDQ_NS + "forIssue"));
            case MEASURE -> List.of(model.createProperty(BDQFFDQ_NS + "forMeasure"));
            case AMENDMENT -> List.of(model.createProperty(BDQFFDQ_NS + "forAmendment"));
            case UNKNOWN -> allForProperties();
        };
    }

    private List<Property> allForProperties() {
        return List.of(
                model.createProperty(BDQFFDQ_NS + "forValidation"),
                model.createProperty(BDQFFDQ_NS + "forIssue"),
                model.createProperty(BDQFFDQ_NS + "forMeasure"),
                model.createProperty(BDQFFDQ_NS + "forAmendment"));
    }
}
