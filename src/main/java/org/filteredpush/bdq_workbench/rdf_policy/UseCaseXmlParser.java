/** UseCaseXmlParser.java
 *
 * Parses BDQ use case definitions from bdquc-style XML or RDF (RDF/XML, Turtle, JSON-LD) sources.
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.vocabulary.RDFS;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Static utility that loads {@link UseCase} definitions from a single file, tolerating both
 * loosely-structured XML and standards-conformant RDF serializations of the same bdquc use case
 * vocabulary.
 *
 * <p>Rather than requiring a single fixed schema, {@link #loadUseCases(Path)} tries two
 * independent strategies and uses whichever one actually produces use cases:
 *
 * <ol>
 *   <li>A lenient DOM walk ({@link #loadUseCasesFromXml}) that treats the file as generic XML —
 *       not necessarily well-formed RDF/XML — and looks, element by element, for anything that
 *       resembles a use case (by tag name or by a nested {@code type} element/attribute
 *       referencing "use case") with an associated policy (by attribute, child element, or a
 *       loosely-matched {@code rdf:resource} reference containing "policy", "profile", or
 *       {@code bdquc/terms/}).
 *   <li>A Jena-based RDF parse ({@link #loadUseCasesFromRdf}) that loads the file as RDF/XML,
 *       Turtle, or JSON-LD (chosen by file extension) and looks for subjects whose URI or
 *       {@code rdf:type} indicates a use case, extracting the linked or literal policy value from
 *       their properties in the same policy/profile/{@code bdquc/terms/} manner.
 * </ol>
 *
 * <p>Which strategy runs first depends on the file's extension (see {@link #isXmlLike(Path)}):
 * for XML-like extensions ({@code .xml}, {@code .rdf}, {@code .owl}) the DOM walk is tried first
 * and the RDF parse is a fallback only if it finds nothing; for other extensions the order is
 * reversed. If neither strategy yields any use case, {@link #loadUseCases(Path)} still returns an
 * empty map rather than throwing — unless the file both looks like RDF/XML (contains
 * {@code <rdf:RDF} or {@code xmlns:rdf=}) and the RDF parse actually failed with an error, in
 * which case that parse failure is surfaced as a likely-malformed-input error.
 */
public final class UseCaseXmlParser {
    private static final Logger LOG = LoggerFactory.getLogger(UseCaseXmlParser.class);
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private UseCaseXmlParser() {
    }

    /**
     * Loads all use cases found in {@code xmlPath}, trying a lenient XML parse and a Jena RDF
     * parse (in an order chosen by the file's extension) and returning the result of whichever
     * one finds use cases first.
     *
     * @param xmlPath path to the use case source file (XML, RDF/XML, Turtle, or JSON-LD)
     * @return use cases keyed by their resolved ID, in first-encountered order; empty if the file
     *     parses successfully but contains no recognizable use case definitions
     * @throws org.filteredpush.bdq_workbench.app.AppException if {@code xmlPath} does not exist,
     *     or if the file appears to be RDF/XML but could not be parsed as such
     */
    public static Map<String, UseCase> loadUseCases(Path xmlPath) {
        if (Files.notExists(xmlPath)) {
            throw new AppException("Use case file not found: " + xmlPath);
        }
        LOG.debug("Loading use cases from {}", xmlPath.toAbsolutePath());
        boolean xmlLike = isXmlLike(xmlPath);
        ParseAttempt xmlAttempt = ParseAttempt.empty();
        ParseAttempt rdfAttempt = ParseAttempt.empty();
        if (xmlLike) {
            xmlAttempt = loadUseCasesFromXml(xmlPath);
            if (!xmlAttempt.useCases().isEmpty()) {
                LOG.debug("Loaded {} use cases from XML parsing: {}", xmlAttempt.useCases().size(), xmlPath);
                return xmlAttempt.useCases();
            }
        }

        rdfAttempt = loadUseCasesFromRdf(xmlPath);
        if (!rdfAttempt.useCases().isEmpty()) {
            LOG.debug("Loaded {} use cases from RDF parsing: {}", rdfAttempt.useCases().size(), xmlPath);
            return rdfAttempt.useCases();
        }

        if (!xmlLike) {
            xmlAttempt = loadUseCasesFromXml(xmlPath);
            if (!xmlAttempt.useCases().isEmpty()) {
                LOG.debug("Loaded {} use cases from XML fallback parsing: {}", xmlAttempt.useCases().size(), xmlPath);
                return xmlAttempt.useCases();
            }
        }
        AppException parseFailure = targetedParseFailure(xmlPath, xmlAttempt, rdfAttempt);
        if (parseFailure != null) {
            throw parseFailure;
        }
        return Map.of();
    }

    private static ParseAttempt loadUseCasesFromXml(Path xmlPath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(xmlPath.toFile());

            Map<String, UseCase> result = new LinkedHashMap<>();
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                if (!(isUseCaseElement(element) || isTypedUseCaseElement(element))) {
                    continue;
                }
                String policy = extractPolicy(element);
                if (policy.isBlank()) {
                    continue;
                }
                String id = firstNonBlank(
                        getAttributeAny(element, "id", "identifier", "uuid", "about", "uri", "resource"),
                        element.getAttributeNS(RDF_NS, "about"),
                        policy);
                String label = firstNonBlank(
                        getAttributeAny(element, "name", "label", "title"),
                        childValue(element, Set.of("name", "label", "title")),
                        id);
                result.putIfAbsent(id, new UseCase(id, label, policy));
            }
            return new ParseAttempt(result, null);
        } catch (Exception e) {
            return new ParseAttempt(Map.of(), new AppException(
                    "Unable to parse use case XML from " + xmlPath + ": " + conciseMessage(e), e));
        }
    }

    private static ParseAttempt loadUseCasesFromRdf(Path rdfPath) {
        Model model;
        try {
            LOG.debug("Attempting RDF parse for use cases: {}", rdfPath.toAbsolutePath());
            model = readModel(rdfPath);
        } catch (AppException e) {
            LOG.debug("RDF parse failed for {}: {}", rdfPath, e.getMessage());
            return new ParseAttempt(Map.of(), e);
        }

        Map<String, UseCase> result = new LinkedHashMap<>();
        var subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.nextResource();
            if (!isUseCaseResource(subject, model)) {
                continue;
            }
            String policy = extractPolicy(subject);
            if (policy.isBlank()) {
                continue;
            }
            String id = firstNonBlank(subject.getURI(), subject.toString(), policy);
            String label = firstNonBlank(
                    literalValue(subject.getProperty(RDFS.label)),
                    id);
            result.putIfAbsent(id, new UseCase(id, label, policy));
        }
        return new ParseAttempt(result, null);
    }

    private static Model readModel(Path rdfPath) {
        List<Lang> languages = orderedLangCandidates(rdfPath);
        RiotException lastRiot = null;
        IOException lastIo = null;
        for (Lang lang : languages) {
            try (InputStream in = Files.newInputStream(rdfPath)) {
                Model model = ModelFactory.createDefaultModel();
                RDFParser.source(in)
                        .base(rdfPath.toUri().toString())
                        .lang(lang)
                        .parse(model);
                return model;
            } catch (RiotException e) {
                lastRiot = e;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new AppException("Unable to read use case RDF from " + rdfPath, lastIo);
        }
        throw new AppException("Unable to parse use case RDF from " + rdfPath + ": " + conciseMessage(lastRiot), lastRiot);
    }

    private static List<Lang> orderedLangCandidates(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".rdf") || name.endsWith(".owl")) {
            return List.of(Lang.RDFXML);
        }
        if (name.endsWith(".ttl")) {
            return List.of(Lang.TURTLE);
        }
        if (name.endsWith(".jsonld") || name.endsWith(".json")) {
            return List.of(Lang.JSONLD);
        }
        return List.of(Lang.RDFXML, Lang.TURTLE, Lang.JSONLD);
    }

    private static boolean isUseCaseResource(Resource resource, Model model) {
        if (resource.getURI() != null && isUseCaseToken(resource.getURI())) {
            return true;
        }
        var types = model.listObjectsOfProperty(resource, model.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));
        while (types.hasNext()) {
            RDFNode type = types.next();
            if (type.isResource()) {
                Resource typeResource = type.asResource();
                if ((typeResource.getURI() != null && isUseCaseToken(typeResource.getURI()))
                        || isUseCaseToken(typeResource.getLocalName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUseCaseToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = normalizedName(value);
        return normalized.contains("usecase") || normalized.contains("use_case") || normalized.contains("use-case");
    }

    private static String extractPolicy(Resource resource) {
        var iterator = resource.listProperties();
        while (iterator.hasNext()) {
            var statement = iterator.nextStatement();
            String predicate = normalizedName(statement.getPredicate().getLocalName() == null
                    ? statement.getPredicate().getURI()
                    : statement.getPredicate().getLocalName());
            if (statement.getObject().isResource()) {
                Resource object = statement.getResource();
                String objectId = object.getURI() == null ? object.toString() : object.getURI();
                if (predicate.contains("policy") || predicate.contains("profile")
                        || predicate.contains("isversionof")
                        || predicate.contains("usecase")
                        || normalizedName(objectId).contains("policy")
                        || normalizedName(objectId).contains("profile")
                        || normalizedName(objectId).contains("bdquc/terms/")) {
                    return objectId;
                }
            }
            if (statement.getObject().isLiteral()) {
                String literal = statement.getString().trim();
                if (predicate.contains("policy") || predicate.contains("profile")
                        || predicate.contains("isversionof")
                        || predicate.contains("usecase")
                        || normalizedName(literal).contains("policy")
                        || normalizedName(literal).contains("profile")
                        || normalizedName(literal).contains("bdquc/terms/")) {
                    return literal;
                }
            }
        }
        return "";
    }

    private static String literalValue(org.apache.jena.rdf.model.Statement statement) {
        if (statement == null || !statement.getObject().isLiteral()) {
            return "";
        }
        return statement.getString();
    }

    private static boolean isUseCaseElement(Element element) {
        String name = normalizedLocalName(element);
        return name.contains("usecase") || name.contains("use_case") || name.contains("use-case");
    }

    private static String extractPolicy(Element element) {
        return firstNonBlank(
                getAttributeAny(element, "policy", "profile", "qualityprofile", "policyid", "policyref", "isversionof"),
                childResourceOrText(element, Set.of("policy", "profile", "qualityprofile", "isversionof")),
                firstResourceByObjectHint(element));
    }

    private static String childResourceOrText(Element parent, Set<String> targetNames) {
        NodeList descendants = parent.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Node node = descendants.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (!targetNames.contains(normalizedLocalName(child))) {
                continue;
            }
            String resource = firstNonBlank(
                    child.getAttributeNS(RDF_NS, "resource"),
                    getAttributeAny(child, "resource", "about", "uri", "href"));
            if (!resource.isBlank()) {
                return resource;
            }
            String text = child.getTextContent();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private static boolean isTypedUseCaseElement(Element element) {
        NodeList descendants = element.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Node node = descendants.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            String local = normalizedLocalName(child);
            if (!"type".equals(local)) {
                continue;
            }
            String resource = firstNonBlank(
                    child.getAttributeNS(RDF_NS, "resource"),
                    getAttributeAny(child, "resource", "about", "uri", "href"),
                    child.getTextContent());
            if (isUseCaseToken(resource)) {
                return true;
            }
        }
        return false;
    }

    private static String firstResourceByObjectHint(Element parent) {
        NodeList descendants = parent.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Node node = descendants.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            String resource = firstNonBlank(
                    child.getAttributeNS(RDF_NS, "resource"),
                    getAttributeAny(child, "resource", "about", "uri", "href"));
            if (resource.isBlank()) {
                continue;
            }
            String normalized = normalizedName(resource);
            if (normalized.contains("policy") || normalized.contains("profile")
                    || normalized.contains("bdquc/terms/")) {
                return resource;
            }
        }
        return "";
    }

    private static String childValue(Element parent, Set<String> targetNames) {
        NodeList descendants = parent.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Node node = descendants.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (!targetNames.contains(normalizedLocalName(child))) {
                continue;
            }
            String value = child.getTextContent();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String getAttributeAny(Element element, String... names) {
        for (String name : names) {
            String direct = element.getAttribute(name);
            if (direct != null && !direct.isBlank()) {
                return direct.trim();
            }
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                String normalized = normalizedName(attribute.getNodeName());
                if (normalized.equals(normalizedName(name)) && !attribute.getNodeValue().isBlank()) {
                    return attribute.getNodeValue().trim();
                }
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizedLocalName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isBlank()) {
            return normalizedName(local);
        }
        return normalizedName(element.getNodeName());
    }

    private static String normalizedName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }

    private static boolean isXmlLike(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".rdf") || name.endsWith(".owl");
    }

    private static AppException targetedParseFailure(Path path, ParseAttempt xmlAttempt, ParseAttempt rdfAttempt) {
        boolean looksLikeRdfXml = isXmlLike(path) && containsRdfXmlMarkers(path);
        if (looksLikeRdfXml && rdfAttempt.error() != null) {
            return new AppException(
                    "Use case source appears malformed or invalid RDF/XML: " + path + ". "
                            + conciseMessage(rdfAttempt.error()),
                    rdfAttempt.error());
        }
        if (xmlAttempt.error() != null) {
            return xmlAttempt.error();
        }
        return null;
    }

    private static boolean containsRdfXmlMarkers(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return content.contains("<rdf:rdf") || content.contains("xmlns:rdf=");
        } catch (IOException e) {
            return false;
        }
    }

    private static String conciseMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "no parser details available";
        }
        return throwable.getMessage().replaceAll("\\s+", " ").trim();
    }

    private record ParseAttempt(Map<String, UseCase> useCases, AppException error) {
        private static ParseAttempt empty() {
            return new ParseAttempt(Map.of(), null);
        }
    }
}
