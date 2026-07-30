package org.filteredpush.bdq_workbench.rdf_policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.UseCase;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses use case definitions from bdquc-style XML files. */
public final class UseCaseXmlParser {
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private UseCaseXmlParser() {
    }

    public static Map<String, UseCase> loadUseCases(Path xmlPath) {
        if (Files.notExists(xmlPath)) {
            throw new AppException("Use case file not found: " + xmlPath);
        }
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
                if (!isUseCaseElement(element)) {
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
            return result;
        } catch (Exception e) {
            throw new AppException("Unable to parse use cases from " + xmlPath, e);
        }
    }

    private static boolean isUseCaseElement(Element element) {
        String name = normalizedLocalName(element);
        return name.contains("usecase") || name.contains("use_case") || name.contains("use-case");
    }

    private static String extractPolicy(Element element) {
        return firstNonBlank(
                getAttributeAny(element, "policy", "profile", "qualityprofile", "policyid", "policyref"),
                childResourceOrText(element, Set.of("policy", "profile", "qualityprofile")));
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
        String lower = raw.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }
}
