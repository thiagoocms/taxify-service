package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/** NFC-e, model 65 - today an NF-e with {@code ide_modelo = '65'} (FD-RN-14). */
@Entity
@DiscriminatorValue("NFCE")
public class Nfce extends GoodsInvoice {

    public static final String MODEL = "65";

    protected Nfce() {
    }

    public Nfce(Company company, DocumentSourceKey sourceSystem) {
        super(company, sourceSystem);
        setModel(MODEL);
    }

    @Override
    public FiscalDocumentType getDocumentType() {
        return FiscalDocumentType.NFCE;
    }

    @Override
    protected String requiredModel() {
        return MODEL;
    }

    @Override
    protected FiscalDocumentInvariant modelAndKeyInvariant() {
        return FiscalDocumentInvariant.I11_NFCE_MODEL_AND_KEY;
    }
}
