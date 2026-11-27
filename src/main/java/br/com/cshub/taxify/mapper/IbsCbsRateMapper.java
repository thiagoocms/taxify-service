package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.config.ClockConfig;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.reform.IbsCbsRateChange;
import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy;
import br.com.cshub.taxify.dto.reform.IbsCbsRateChangeDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateReferenceDTO;
import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * IBS/CBS reference rates per year: body to entity (texts trimmed, valid rates at scale 4, unknown status = null for
 * the validation to refuse) and entity to the console row, the firms' row and the history event.
 */
@Component
public class IbsCbsRateMapper extends AbstractMapper<IbsCbsRate, IbsCbsRateDTO> {

    @Override
    public IbsCbsRate toEntity(IbsCbsRateDTO source) {
        IbsCbsRate entity = new IbsCbsRate(source.year(), IbsCbsRatePolicy.normalize(
                IbsCbsRateStatus.parse(source.status()).orElse(null), source.cbsRate(), source.ibsStateRate(),
                source.ibsMunicipalRate(), source.legalBasis(), source.sourceUrl()));
        entity.setVersion(source.version());
        return entity;
    }

    @Override
    public IbsCbsRateDTO toModel(IbsCbsRate source) {
        return new IbsCbsRateDTO(source.getYear(), source.getCbsRate(), source.getIbsStateRate(),
                source.getIbsMunicipalRate(), name(source.getStatus()), source.generatesFindings(), source.getLegalBasis(),
                source.getSourceUrl(), source.getVersion(), source.getChangedBy(), recife(source.getChangedAt()));
    }

    /** Only the editable content: the version and the audit stay the persisted ones. */
    @Override
    public void merge(IbsCbsRate source, IbsCbsRate target) {
        target.apply(source.values());
    }

    public IbsCbsRateReferenceDTO toReference(IbsCbsRate source) {
        return new IbsCbsRateReferenceDTO(source.getYear(), source.getCbsRate(), source.getIbsStateRate(),
                source.getIbsMunicipalRate(), name(source.getStatus()), source.generatesFindings(), source.getLegalBasis(),
                source.getSourceUrl());
    }

    public IbsCbsRateChangeDTO toChange(IbsCbsRateChange source) {
        return new IbsCbsRateChangeDTO(name(source.getAction()), name(source.getStatusBefore()), name(source.getStatusAfter()),
                source.getCbsBefore(), source.getCbsAfter(), source.getIbsStateBefore(), source.getIbsStateAfter(),
                source.getIbsMunicipalBefore(), source.getIbsMunicipalAfter(), source.getLegalBasisAfter(),
                source.getSourceUrlAfter(), source.getChangedBy(), recife(source.getChangedAt()));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static OffsetDateTime recife(LocalDateTime at) {
        return at == null ? null : at.atZone(ClockConfig.RECIFE).toOffsetDateTime();
    }
}
