package br.com.cshub.taxify.enumerated;

/** Where a note came from first - stored in fiscal_xml_documents.source_system. */
public enum FiscalXmlSource {
    SIEG,
    /** NFeDistribuicaoDFe of the Ambiente Nacional, with the company's A1. */
    SEFAZ_DIST,
    /** Uploaded by hand (single XML or zip). */
    UPLOAD
}
