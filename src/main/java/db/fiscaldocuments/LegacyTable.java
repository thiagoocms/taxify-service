package db.fiscaldocuments;

/**
 * The four tables the remodel replaces (FD-RN-26). V87 renames them to {@code legacy_*} in one atomic
 * {@code RENAME TABLE}, V88 compares them with the new ones and V89 drops them - children first (FD-RN-53).
 */
enum LegacyTable {
    EVENTS("fiscal_xml_events"),
    ITEMS("fiscal_xml_document_items"),
    DOCUMENTS("fiscal_xml_documents"),
    SERVICE_INVOICES("service_invoices");

    private final String originalName;

    LegacyTable(String originalName) {
        this.originalName = originalName;
    }

    String originalName() {
        return originalName;
    }

    String legacyName() {
        return "legacy_" + originalName;
    }

    /** Where the table is on this database. */
    enum Location { ORIGINAL, LEGACY, ABSENT }

    Location locate(CutoverDatabase db) {
        boolean original = db.tableExists(originalName);
        boolean legacy = db.tableExists(legacyName());
        if (original && legacy) {
            throw new IllegalStateException("fiscal documents cutover: both " + originalName + " and " + legacyName()
                    + " exist - an old application image recreated the table after the rename? Nothing was changed;"
                    + " compare the two by hand before going on");
        }
        return original ? Location.ORIGINAL : legacy ? Location.LEGACY : Location.ABSENT;
    }
}
