package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every role the company has in a CT-e ({@code cte_is_*}); a company can be, say, sender and taker at once. The
 * document keeps a single main role ({@link #primaryRole()}).
 */
@Embeddable
public record CteParticipation(
        @Column(name = "cte_is_issuer") Boolean issuer,
        @Column(name = "cte_is_sender") Boolean sender,
        @Column(name = "cte_is_shipper") Boolean shipper,
        @Column(name = "cte_is_receiver") Boolean receiver,
        @Column(name = "cte_is_recipient") Boolean recipient,
        @Column(name = "cte_is_taker") Boolean taker
) {

    /** I-15: at least one of the roles. */
    public boolean hasAnyRole() {
        return byPrecedence().containsValue(Boolean.TRUE);
    }

    /**
     * FD-RN-08: the main role by the precedence ISSUER > TAKER > RECIPIENT > SENDER > SHIPPER > RECEIVER;
     * {@code THIRD_PARTY} when the company has none of them (which a new CT-e cannot have, I-15).
     */
    public CompanyDocumentRole primaryRole() {
        return byPrecedence().entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(CompanyDocumentRole.THIRD_PARTY);
    }

    private Map<CompanyDocumentRole, Boolean> byPrecedence() {
        Map<CompanyDocumentRole, Boolean> roles = new LinkedHashMap<>();
        roles.put(CompanyDocumentRole.ISSUER, issuer);
        roles.put(CompanyDocumentRole.TAKER, taker);
        roles.put(CompanyDocumentRole.RECIPIENT, recipient);
        roles.put(CompanyDocumentRole.SENDER, sender);
        roles.put(CompanyDocumentRole.SHIPPER, shipper);
        roles.put(CompanyDocumentRole.RECEIVER, receiver);
        return roles;
    }
}
