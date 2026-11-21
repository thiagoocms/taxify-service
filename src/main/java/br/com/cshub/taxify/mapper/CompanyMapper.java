package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.TaxRegimes;
import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CompanyMapper extends AbstractMapper<Company, CompanyDTO> {

    private final DocumentMapper documentMapper;

    public CompanyMapper(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Override
    protected Company toEntity(CompanyDTO source) {
        Company entity = new Company();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setName(source.name());
        entity.setTradeName(source.tradeName());
        entity.setPhone(source.phone());
        entity.setUf(source.uf());
        applyTaxRegime(entity, source);
        entity.setStateRegistration(source.stateRegistration());
        entity.setCompanyGroup(source.companyGroup());
        entity.setActive(source.active());
        entity.setClientSince(source.clientSince());
        entity.setClientUntil(source.clientUntil());
        entity.setDocument(documentMapper.toEntity(source.documentType(), source.documentNumber()));
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected CompanyDTO toModel(Company source) {
        return new CompanyDTO(
                source.getId().toString(),
                source.getDocument().getNumber(),
                source.getDocument().getType().toString(),
                source.getName(),
                source.getTradeName(),
                source.getPhone(),
                source.getUf(),
                source.getTaxRegime(),
                source.getTaxRegimeType(),
                source.getBusinessActivity(),
                source.getSimplesServiceAnnex(),
                source.getStateRegistration(),
                source.getCompanyGroup(),
                source.getActive(),
                source.getClientSince(),
                source.getClientUntil(),
                source.getAcessoriasId(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Company source, Company target) {
        target.setName(source.getName());
        target.setTradeName(source.getTradeName());
        target.setPhone(source.getPhone());
        target.setUf(source.getUf());
        target.setTaxRegime(source.getTaxRegime());
        target.setTaxRegimeType(source.getTaxRegimeType());
        target.setBusinessActivity(source.getBusinessActivity());
        target.setSimplesServiceAnnex(source.getSimplesServiceAnnex());
        target.setStateRegistration(source.getStateRegistration());
        target.setCompanyGroup(source.getCompanyGroup());
        target.setActive(source.getActive());
        target.setClientSince(source.getClientSince());
        target.setClientUntil(source.getClientUntil());
        target.setDeleted(source.isDeleted());
    }

    /**
     * Structured fields win; the label follows them. A label without structured fields (an
     * older client, or a pasted text) is parsed instead.
     */
    private static void applyTaxRegime(Company entity, CompanyDTO source) {
        entity.setSimplesServiceAnnex(source.simplesServiceAnnex());
        if (source.taxRegimeType() == null) {
            entity.applyTaxRegimeLabel(blankToNull(source.taxRegime()));
            return;
        }
        entity.setTaxRegimeType(source.taxRegimeType());
        entity.setBusinessActivity(source.businessActivity());
        TaxRegimes.Parsed label = TaxRegimes.parse(source.taxRegime());
        boolean labelAgrees = label != null && label.regime() == source.taxRegimeType()
                && label.activity() == source.businessActivity();
        entity.setTaxRegime(labelAgrees ? source.taxRegime().strip()
                : TaxRegimes.label(source.taxRegimeType(), source.businessActivity()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
