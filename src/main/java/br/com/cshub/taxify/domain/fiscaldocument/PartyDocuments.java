package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;

/**
 * The document of a party of an imported XML by the tag it came in (I-06, decision of 2026-11-28; only the import -
 * the copy of V87 keeps what the old tables had): a {@code CNPJ} tag gives a CNPJ, a {@code CPF} tag a CPF.
 * <ul>
 *   <li>only digits and shorter than the tag's size (14 / 11) - the XML dropped the zeros on the left - is completed
 *   with zeros ({@link ImportAdjustments.Kind#PARTY_PADDED});</li>
 *   <li>longer than the size, anything but digits, or both tags with text (ambiguous): kept as it came, so I-06 refuses
 *   the document ({@link ImportAdjustments.Kind#PARTY_REFUSED}) - the type is dropped when ambiguous;</li>
 *   <li>no tag with text: no Brazilian document.</li>
 * </ul>
 */
public final class PartyDocuments {

    /** The document and its type as the model keeps them; both null = no Brazilian document. */
    public record Tagged(String document, TaxpayerDocumentType type) {

        static final Tagged NONE = new Tagged(null, null);
    }

    private PartyDocuments() {
    }

    /** {@code party} names the party in the record ({@code Type.party}), e.g. {@code Nfe.issuer}. */
    public static Tagged of(String cnpj, String cpf, String party) {
        boolean hasCnpj = FiscalText.hasText(cnpj);
        boolean hasCpf = FiscalText.hasText(cpf);
        if (hasCnpj && hasCpf) {
            ImportAdjustments.record(ImportAdjustments.Kind.PARTY_REFUSED, party + ".ambiguous", cnpj.length());
            return new Tagged(cnpj, null);
        }
        if (hasCnpj) {
            return tagged(cnpj, TaxpayerDocumentType.CNPJ, TaxpayerDocumentType.CNPJ_LENGTH, party);
        }
        if (hasCpf) {
            return tagged(cpf, TaxpayerDocumentType.CPF, TaxpayerDocumentType.CPF_LENGTH, party);
        }
        return Tagged.NONE;
    }

    private static Tagged tagged(String document, TaxpayerDocumentType type, int size, String party) {
        String field = party + "." + type.name();
        if (!isDigits(document) || document.length() > size) {
            ImportAdjustments.record(ImportAdjustments.Kind.PARTY_REFUSED, field, document.length());
            return new Tagged(document, type);
        }
        if (document.length() < size) {
            ImportAdjustments.record(ImportAdjustments.Kind.PARTY_PADDED, field, document.length());
            return new Tagged("0".repeat(size - document.length()) + document, type);
        }
        return new Tagged(document, type);
    }

    private static boolean isDigits(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
