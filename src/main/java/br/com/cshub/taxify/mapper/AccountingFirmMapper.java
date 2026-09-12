package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.dto.accountingfirm.AccountingFirmDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AccountingFirmMapper extends AbstractMapper<AccountingFirm, AccountingFirmDTO> {

    @Override
    protected AccountingFirm toEntity(AccountingFirmDTO source) {
        AccountingFirm entity = new AccountingFirm();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setName(source.name());
        entity.setSiegApiKey(source.siegApiKey());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected AccountingFirmDTO toModel(AccountingFirm source) {
        return new AccountingFirmDTO(
                source.getId().toString(),
                source.getName(),
                source.getSiegApiKey(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(AccountingFirm source, AccountingFirm target) {
        target.setName(source.getName());
        target.setSiegApiKey(source.getSiegApiKey());
        target.setDeleted(source.isDeleted());
    }
}
