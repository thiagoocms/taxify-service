package br.com.cshub.taxify.service.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.ImportAdjustments;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The log lines of the import of fiscal documents (decisions of 2026-11-28): what the model adjusted
 * ({@link ImportAdjustments}) and the documents refused by an invariant. Only ids, the type, the field, sizes, the
 * position in the batch and a short hash of the access key - never the key, a CNPJ, a value or a name. Each adjustment
 * also counts in the Micrometer counter {@value #METRIC} (tag {@code kind}).
 */
public final class ImportLog {

    static final String METRIC = "fiscal.document.import.adjustments";
    private static final Logger log = LoggerFactory.getLogger(ImportLog.class);
    private static final int HASH_HEX = 10;

    private ImportLog() {
    }

    /** Logs and counts each adjustment of one imported document. */
    public static void adjustments(Company company, String type, DocumentSourceKey source, int position,
                                   List<ImportAdjustments.Adjustment> adjustments) {
        for (ImportAdjustments.Adjustment adjustment : adjustments) {
            switch (adjustment.kind()) {
                case DECIMAL_ROUNDED -> log.info("fiscal.document.decimal.rounded companyId={} type={} field={} decimals={} source={}"
                        + " position={}", idOf(company), type, adjustment.field(), adjustment.size(), sourceOf(source), position);
                case PARTY_PADDED -> log.info("fiscal.document.party.padded companyId={} type={} field={} digits={} source={}"
                        + " position={}", idOf(company), type, adjustment.field(), adjustment.size(), sourceOf(source), position);
                case PARTY_REFUSED -> log.warn("fiscal.document.party.refused companyId={} type={} field={} length={} source={}"
                        + " position={}", idOf(company), type, adjustment.field(), adjustment.size(), sourceOf(source), position);
            }
            Metrics.counter(METRIC, "kind", adjustment.kind().name().toLowerCase(Locale.ROOT)).increment();
        }
    }

    /** A document not stored because it breaks an invariant (or the model refused it while being built). */
    public static void violated(Company company, String type, String invariant, DocumentSourceKey source, int position,
                                String accessKey) {
        log.warn("fiscal.document.invariant.violated companyId={} type={} invariant={} source={} position={} keyHash={}",
                idOf(company), type, invariant, sourceOf(source), position, shortHash(accessKey));
    }

    public static void violated(Company company, String type, FiscalDocumentInvariantException e, DocumentSourceKey source,
                                int position, String accessKey) {
        violated(company, type, String.valueOf(e.getInvariant()), source, position, accessKey);
    }

    /** The first {@value #HASH_HEX} hex digits of the SHA-256 of {@code value} - enough to find it, not to read it. */
    static String shortHash(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Object idOf(Company company) {
        return company == null ? null : company.getId();
    }

    private static String sourceOf(DocumentSourceKey source) {
        return source == null ? null : source.value();
    }
}
