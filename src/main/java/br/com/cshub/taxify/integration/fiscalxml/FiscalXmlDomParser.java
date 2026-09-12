package br.com.cshub.taxify.integration.fiscalxml;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;

/**
 * XXE-hardened DOM parsing shared by {@link br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser}
 * (read-only) and {@link br.com.cshub.taxify.service.FiscalXmlRectificationService} (read
 * + rewrite) - kept in one place so the hardening (no DOCTYPE, no external entities)
 * can't drift. Not provider-specific: NFe/CTe XML parsing, not a SIEG concern.
 */
public final class FiscalXmlDomParser {

    private FiscalXmlDomParser() {
    }

    public static Document parse(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xmlBytes));
    }
}
