package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/** NF-e, model 55. Only the queries that also filter the model 55 today (manifestação) use {@code Nfe} (FD-RN-14). */
@Entity
@DiscriminatorValue("NFE")
public class Nfe extends GoodsInvoice {

    public static final String MODEL = "55";

    protected Nfe() {
    }

    public Nfe(Company company, DocumentSourceKey sourceSystem) {
        super(company, sourceSystem);
        setModel(MODEL);
    }

    @Override
    public FiscalDocumentType getDocumentType() {
        return FiscalDocumentType.NFE;
    }

    @Override
    protected String requiredModel() {
        return MODEL;
    }

    @Override
    protected FiscalDocumentInvariant modelAndKeyInvariant() {
        return FiscalDocumentInvariant.I10_NFE_MODEL_AND_KEY;
    }
}
