package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.company.StateRegistration;
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
        // normalized to "IE/UF; IE/UF" when it reads as such; anything else (an IE without UF) is kept as typed
        var registrations = StateRegistration.parse(source.stateRegistration());
        if (registrations.isEmpty()) {
            entity.setStateRegistration(blankToNull(source.stateRegistration()));
        } else {
            entity.replaceStateRegistrations(registrations);
        }
        entity.setOpeningDate(source.openingDate());
        entity.setEmail(blankToNull(source.email()));
        entity.setMunicipalRegistration(blankToNull(source.municipalRegistration()));
        entity.setMainCnae(digits(source.mainCnae()));
        entity.setSecondaryCnaes(cnaeList(source.secondaryCnaes()));
        entity.setAddressStreet(blankToNull(source.addressStreet()));
        entity.setAddressNumber(blankToNull(source.addressNumber()));
        entity.setAddressComplement(blankToNull(source.addressComplement()));
        entity.setAddressDistrict(blankToNull(source.addressDistrict()));
        entity.setAddressCityCode(digits(source.addressCityCode()));
        entity.setAddressCity(blankToNull(source.addressCity()));
        entity.setAddressZipCode(digits(source.addressZipCode()));
        entity.setCompanyGroup(source.companyGroup());
        entity.setActive(source.active());
        entity.setClientSince(source.clientSince());
        entity.setClientUntil(source.clientUntil());
        entity.setDocument(documentMapper.toEntity(source.documentType(), source.documentNumber()));
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    /** Public entry point for services outside the CRUD base. */
    public CompanyDTO toDto(Company source) {
        return toModel(source);
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
                source.getOpeningDate(),
                source.getEmail(),
                source.getMunicipalRegistration(),
                source.getMainCnae(),
                source.getSecondaryCnaes(),
                source.getAddressStreet(),
                source.getAddressNumber(),
                source.getAddressComplement(),
                source.getAddressDistrict(),
                source.getAddressCityCode(),
                source.getAddressCity(),
                source.getAddressZipCode(),
                null,
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
        target.setOpeningDate(source.getOpeningDate());
        target.setEmail(source.getEmail());
        target.setMunicipalRegistration(source.getMunicipalRegistration());
        target.setMainCnae(source.getMainCnae());
        target.setSecondaryCnaes(source.getSecondaryCnaes());
        target.setAddressStreet(source.getAddressStreet());
        target.setAddressNumber(source.getAddressNumber());
        target.setAddressComplement(source.getAddressComplement());
        target.setAddressDistrict(source.getAddressDistrict());
        target.setAddressCityCode(source.getAddressCityCode());
        target.setAddressCity(source.getAddressCity());
        target.setAddressZipCode(source.getAddressZipCode());
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

    private static String digits(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /** "4781-4/00, 4782-2/01" → "4781400,4782201". */
    private static String cnaeList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String joined = java.util.Arrays.stream(value.split("[,;\\s]+"))
                .map(CompanyMapper::digits)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
