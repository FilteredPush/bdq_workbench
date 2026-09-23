/** InformationElementIndex.java
 *
 * Resolves the Darwin Core information elements referenced by ratified BDQ tests.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.filteredpush.bdq_workbench.model.TestType;

/**
 * Index of acted-upon and consulted Darwin Core information elements for BDQ tests.
 */
public class InformationElementIndex {
	private static final String BDQFFDQ = "https://rs.tdwg.org/bdqffdq/terms/";
	private static final String DCTERMS = "http://purl.org/dc/terms/";
	private static final String DWC = "http://rs.tdwg.org/dwc/terms/";

	private final Model model;

	/**
	 * Creates an index over the given RDF definition files.
	 *
	 * @param rdfDefinitions the definition files to load
	 */
	public InformationElementIndex(List<Path> rdfDefinitions) {
		this.model = RdfDefinitionsLoader.load(rdfDefinitions);
	}

	/**
	 * Resolves the union of Darwin Core information elements referenced by {@code tests}.
	 *
	 * @param tests the tests to inspect
	 * @return distinct Darwin Core local term names in first-encountered order
	 */
	public List<String> termsFor(List<TestDefinition> tests) {
		Set<String> terms = new LinkedHashSet<>();
		for (TestDefinition test : tests) {
			terms.addAll(termsFor(test.id(), test.type()));
		}
		return List.copyOf(terms);
	}

	/**
	 * Resolves the Darwin Core information elements referenced by one test definition.
	 *
	 * @param testId the test IRI
	 * @param testType the test type
	 * @return distinct Darwin Core local term names in first-encountered order
	 */
	public List<String> termsFor(String testId, TestType testType) {
		Resource test = model.createResource(testId);
		Set<String> terms = new LinkedHashSet<>();
		for (Resource variant : variantsOf(test)) {
			collectTerms(variant, "hasActedUponInformationElement", terms);
			collectTerms(variant, "hasConsultedInformationElement", terms);
		}
		return List.copyOf(terms);
	}

	/**
	 * Collects a resource together with any version-related variants used by the ratified test RDF.
	 *
	 * @param resource the test resource
	 * @return the resource and any linked version variants
	 */
	private List<Resource> variantsOf(Resource resource) {
		Property isVersionOf = model.createProperty(DCTERMS, "isVersionOf");
		Set<Resource> variants = new LinkedHashSet<>();
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
		return List.copyOf(variants);
	}

	/**
	 * Collects Darwin Core terms from one information-element property on one test resource.
	 *
	 * @param testResource the test resource to inspect
	 * @param propertyLocalName the BDQ FFDQ property naming the information elements
	 * @param terms the output set to add resolved Darwin Core local names to
	 */
	private void collectTerms(Resource testResource, String propertyLocalName, Set<String> terms) {
		Property property = model.createProperty(BDQFFDQ, propertyLocalName);
		Property composedOf = model.createProperty(BDQFFDQ, "composedOf");
		StmtIterator elements = testResource.listProperties(property);
		while (elements.hasNext()) {
			RDFNode element = elements.nextStatement().getObject();
			if (!element.isResource()) {
				continue;
			}
			StmtIterator composed = element.asResource().listProperties(composedOf);
			while (composed.hasNext()) {
				RDFNode value = composed.nextStatement().getObject();
				if (!value.isResource()) {
					continue;
				}
				String uri = value.asResource().getURI();
				if (uri != null && uri.startsWith(DWC)) {
					terms.add(uri.substring(DWC.length()));
				}
			}
		}
	}
}
