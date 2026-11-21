package br.com.cshub.taxify.integration.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every BillingProvider bean, by type - MANUAL has none. */
@Component
public class BillingProviderRegistry {

    private final Map<BillingProviderType, BillingProvider> providers = new EnumMap<>(BillingProviderType.class);

    public BillingProviderRegistry(List<BillingProvider> providers) {
        providers.forEach(provider -> this.providers.put(provider.type(), provider));
    }

    public Optional<BillingProvider> find(BillingProviderType type) {
        return Optional.ofNullable(type == null ? null : providers.get(type));
    }

    public List<BillingProviderType> available() {
        return List.copyOf(providers.keySet());
    }
}
