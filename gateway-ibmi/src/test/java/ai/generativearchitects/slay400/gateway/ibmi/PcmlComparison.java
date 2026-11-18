package ai.generativearchitects.slay400.gateway.ibmi;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Compares the PCML the compiler wrote with the PCML the repository keeps for the same program: elements by kind and name,
 * attributes by name, and the order of the elements. Comments, white space and the order of attributes do not count. Parsed
 * with the JDK, with DTDs and external entities off.
 */
final class PcmlComparison {

    private PcmlComparison() {
    }

    /** One line per difference; empty when the two documents say the same. */
    static List<String> differences(String expected, String actual) {
        Element left;
        Element right;
        try {
            left = parse(expected);
        } catch (Exception e) {
            return List.of("the fixture is not valid XML (" + e.getClass().getSimpleName() + ")");
        }
        try {
            right = parse(actual);
        } catch (Exception e) {
            return List.of("the compiler's PCML is not valid XML (" + e.getClass().getSimpleName() + ")");
        }
        List<String> found = new ArrayList<>();
        compare(left, right, label(left), found);
        return found;
    }

    static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static void compare(Element expected, Element actual, String where, List<String> found) {
        Map<String, String> left = attributes(expected);
        Map<String, String> right = attributes(actual);
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String other = right.get(entry.getKey());
            if (other == null) {
                found.add(where + ": the compiler wrote no attribute " + entry.getKey() + " (the fixture has \"" + entry.getValue() + "\")");
            } else if (!other.equals(entry.getValue())) {
                found.add(where + ": attribute " + entry.getKey() + " is \"" + other + "\" (the fixture has \"" + entry.getValue() + "\")");
            }
        }
        right.forEach((name, value) -> {
            if (!left.containsKey(name)) {
                found.add(where + ": the compiler wrote attribute " + name + "=\"" + value + "\", which the fixture does not have");
            }
        });
        Map<String, Element> leftChildren = children(expected);
        Map<String, Element> rightChildren = children(actual);
        for (Map.Entry<String, Element> entry : leftChildren.entrySet()) {
            Element other = rightChildren.get(entry.getKey());
            if (other == null) {
                found.add(where + ": the compiler wrote no " + entry.getKey());
            } else {
                compare(entry.getValue(), other, where + " > " + entry.getKey(), found);
            }
        }
        for (String key : rightChildren.keySet()) {
            if (!leftChildren.containsKey(key)) {
                found.add(where + ": the compiler wrote " + key + ", which the fixture does not have");
            }
        }
        List<String> leftOrder = leftChildren.keySet().stream().filter(rightChildren::containsKey).toList();
        List<String> rightOrder = rightChildren.keySet().stream().filter(leftChildren::containsKey).toList();
        if (!leftOrder.equals(rightOrder)) {
            found.add(where + ": the elements are in another order: " + String.join(", ", rightOrder) + " (the fixture has " + String.join(", ", leftOrder) + ")");
        }
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> out = new TreeMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            out.put(attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
        }
        return out;
    }

    private static Map<String, Element> children(Element element) {
        Map<String, Element> out = new LinkedHashMap<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                String key = label(child);
                int n = 2;
                String unique = key;
                while (out.containsKey(unique)) {
                    unique = key + " #" + n++;
                }
                out.put(unique, child);
            }
        }
        return out;
    }

    private static String label(Element element) {
        String name = element.getAttribute("name");
        return element.getTagName() + (name.isEmpty() ? "" : " " + name);
    }
}
