package br.com.cshub.taxify.integration.fiscalxml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reading NFe/CTe/event XML parsed by {@link FiscalXmlDomParser}: elements are looked up by
 * local name in any namespace (the portal namespace, or none on older files). Null-safe - a
 * missing parent or child gives null.
 */
public final class FiscalXmlDom {

    private FiscalXmlDom() {
    }

    /** First descendant of {@code document} named {@code localName}. */
    public static Element first(Document document, String localName) {
        if (document == null) {
            return null;
        }
        return firstOf(document.getElementsByTagNameNS("*", localName));
    }

    /** First descendant of {@code parent} named {@code localName}. */
    public static Element first(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        return firstOf(parent.getElementsByTagNameNS("*", localName));
    }

    /** Text of the first descendant named {@code localName}, as it is in the file (not trimmed). */
    public static String text(Element parent, String localName) {
        Element child = first(parent, localName);
        return child == null ? null : child.getTextContent();
    }

    private static Element firstOf(NodeList nodes) {
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }
}
