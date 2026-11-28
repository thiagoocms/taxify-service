package br.com.cshub.taxify.domain.fiscaldocument;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, B-FD-05): after the switch of the consumers, nothing in
 * {@code src/main} reads or writes the old model - neither its classes nor its tables. The migrations
 * ({@code db/migration}) are the only exception: V87-V89 copy from and drop the old tables by name.
 */
class NoLegacyFiscalModelInMainTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path MIGRATIONS = MAIN.resolve(Path.of("db", "migration"));

    /** Old classes and tables; {@code legacy_} catches the renamed tables of V87. */
    private static final Pattern OLD_NAMES = Pattern.compile("\\b(FiscalXmlDocument|FiscalXmlDocumentItem|FiscalXmlEvent|ServiceInvoice"
            + "|FiscalXmlDocumentRepository|FiscalXmlDocumentItemRepository|FiscalXmlEventRepository|ServiceInvoiceRepository"
            + "|FiscalXmlHeaderExtras|FiscalIbsCbsTotals|FiscalItemTaxDetail|FiscalItemIbsCbs|FiscalXmlSource"
            + "|fiscal_xml_documents|fiscal_xml_document_items|fiscal_xml_events|service_invoices|legacy_[a-z_]+)\\b");

    @Test
    void theOldFiscalModelIsNotUsedOutsideTheMigrations() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(file -> file.toString().endsWith(".java") && !file.startsWith(MIGRATIONS)).forEach(file -> scan(file, found));
        }
        assertThat(found).as("old fiscal model referenced in src/main").isEmpty();
    }

    @Test
    void thePatternSeesTheOldNames() {
        assertThat(OLD_NAMES.matcher("FROM fiscal_xml_documents d").find()).isTrue();
        assertThat(OLD_NAMES.matcher("ServiceInvoice invoice").find()).isTrue();
        assertThat(OLD_NAMES.matcher("legacy_service_invoices").find()).isTrue();
        // what the new model keeps: the service and its counter column, the sync control
        assertThat(OLD_NAMES.matcher("ServiceInvoiceService service_invoices_checked_on FiscalXmlSyncControl").find()).isFalse();
    }

    private static void scan(Path file, List<String> found) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = OLD_NAMES.matcher(lines.get(i));
                if (matcher.find()) {
                    found.add(file + ":" + (i + 1) + " " + matcher.group());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
