package br.com.cshub.taxify.integration.billing;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every BillingProvider bean, by type - MANUAL has none. */
@Component
public class BillingProviderRegistry {

    private final Map<BillingProviderType, BillingProvider> providers = new EnumMap<>(BillingProviderType.class);
    private final MessageService messageService;

    public BillingProviderRegistry(List<BillingProvider> providers, MessageService messageService) {
        this.messageService = messageService;
        providers.forEach(provider -> this.providers.put(provider.type(), provider));
    }

    public Optional<BillingProvider> find(BillingProviderType type) {
        return Optional.ofNullable(type == null ? null : providers.get(type));
    }

    /** The firm's chosen provider, ready for calls - fails clearly for MANUAL or a provider not connected yet. */
    public BillingProvider requireConnected(AccountingFirm firm) {
        if (firm.getBillingProvider() == BillingProviderType.MANUAL) {
            throw new BadRequestException(messageService.getMessage("error.billing.provider.manual"));
        }
        return find(firm.getBillingProvider())
                .filter(provider -> provider.isConnected(firm))
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.billing.provider.not.connected")));
    }

    public boolean isConnected(AccountingFirm firm) {
        return find(firm.getBillingProvider()).map(provider -> provider.isConnected(firm)).orElse(false);
    }

    /** Every option the firm can pick, MANUAL first. */
    public List<BillingProviderType> selectable() {
        List<BillingProviderType> selectable = new ArrayList<>();
        selectable.add(BillingProviderType.MANUAL);
        selectable.addAll(providers.keySet());
        return selectable;
    }
}
