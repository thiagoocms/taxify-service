package br.com.cshub.taxify.integration.contaazul;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.ChargeQuery;
import br.com.cshub.taxify.integration.billing.ExternalCategory;
import br.com.cshub.taxify.integration.billing.ExternalCharge;
import br.com.cshub.taxify.integration.billing.ExternalContract;
import br.com.cshub.taxify.integration.billing.ExternalCustomer;
import br.com.cshub.taxify.service.ContaAzulAuthService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conta Azul as a BillingProvider. What its API gives and how it maps:
 * <ul>
 *   <li>Pessoas (documento) → ExternalCustomer</li>
 *   <li>Contratos (status ATIVO/INATIVO, número, início, próximo vencimento - no value) → ExternalContract</li>
 *   <li>Contas a receber (total/pago/não pago, status) → ExternalCharge; the fee is taken from these</li>
 * </ul>
 */
@Component
public class ContaAzulBillingProvider implements BillingProvider {

    /** Contracts are searched by start date; this window covers any realistic contract. */
    private static final String CONTRACTS_FROM = "2000-01-01";
    /** ids_clientes is a query array - batch it so the URL stays reasonable. */
    private static final int CUSTOMER_BATCH = 40;
    /** Longest due-date range the receivables search accepts (start and end days included). */
    static final int MAX_DUE_RANGE_DAYS = 365;
    private static final DateTimeFormatter CHANGED_SINCE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ContaAzulApiClient client;
    private final ContaAzulAuthService authService;

    public ContaAzulBillingProvider(ContaAzulApiClient client, ContaAzulAuthService authService) {
        this.client = client;
        this.authService = authService;
    }

    @Override
    public BillingProviderType type() {
        return BillingProviderType.CONTA_AZUL;
    }

    @Override
    public boolean isConnected(AccountingFirm firm) {
        return authService.isConnected(firm.getId());
    }

    @Override
    public Optional<ExternalCustomer> findCustomerByDocument(AccountingFirm firm, String document) {
        String digits = DocumentNumbers.onlyDigits(document);
        if (!StringUtils.hasText(digits)) {
            return Optional.empty();
        }
        // Conta Azul may store the document with or without the mask - try both.
        for (String candidate : List.of(digits, DocumentNumbers.format(digits))) {
            Optional<ExternalCustomer> found = client.findPessoasByDocument(firm.getId(), candidate).stream()
                    .filter(pessoa -> digits.equals(DocumentNumbers.onlyDigits(pessoa.documento())))
                    .findFirst()
                    .map(pessoa -> new ExternalCustomer(pessoa.id(), pessoa.nome(), digits));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ExternalContract> listContracts(AccountingFirm firm, String customerId) {
        String to = LocalDate.now().plusYears(10).toString();
        return client.listContratos(firm.getId(), customerId, CONTRACTS_FROM, to).stream()
                .map(contrato -> new ExternalContract(
                        contrato.id(),
                        contrato.numero() == null ? null : String.valueOf(contrato.numero()),
                        contrato.cliente() == null ? customerId : contrato.cliente().id(),
                        "ATIVO".equalsIgnoreCase(contrato.status()) ? SubscriptionStatus.ACTIVE : SubscriptionStatus.INACTIVE,
                        parseDate(contrato.dataInicio()),
                        null,
                        parseDate(contrato.proximoVencimento()),
                        null,
                        null))
                .toList();
    }

    @Override
    public List<ExternalCharge> listCharges(AccountingFirm firm, ChargeQuery query) {
        Map<String, ExternalCharge> charges = new LinkedHashMap<>();
        String changedSince = query.changedSince() == null ? null : query.changedSince().format(CHANGED_SINCE);
        List<String> customers = query.customerIds() == null ? List.of() : query.customerIds();
        for (LocalDate[] window : dueWindows(query.dueFrom(), query.dueTo())) {
            for (int i = 0; i < Math.max(customers.size(), 1); i += CUSTOMER_BATCH) {
                List<String> batch = customers.isEmpty() ? List.of() : customers.subList(i, Math.min(i + CUSTOMER_BATCH, customers.size()));
                client.searchContasAReceber(firm.getId(), batch, query.categoryIds(),
                                window[0].toString(), window[1].toString(), changedSince)
                        .stream()
                        .filter(conta -> conta.id() != null && parseDate(conta.dataVencimento()) != null)
                        .map(ContaAzulBillingProvider::toCharge)
                        // windows don't overlap, but dedupe by id anyway in case the API's bounds are inclusive on both ends
                        .forEach(charge -> charges.put(charge.id(), charge));
            }
        }
        return new ArrayList<>(charges.values());
    }

    /**
     * Conta Azul rejects a due-date range longer than 365 days ("O intervalo de data de
     * vencimento excede o limite máximo permitido de 365 dias") - split the requested one
     * into consecutive, non-overlapping windows that fit.
     */
    static List<LocalDate[]> dueWindows(LocalDate from, LocalDate to) {
        List<LocalDate[]> windows = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusDays(MAX_DUE_RANGE_DAYS - 1);
            if (end.isAfter(to)) {
                end = to;
            }
            windows.add(new LocalDate[]{start, end});
            start = end.plusDays(1);
        }
        return windows;
    }

    @Override
    public List<ExternalCategory> listRevenueCategories(AccountingFirm firm) {
        return client.listCategoriasReceita(firm.getId()).stream()
                .map(categoria -> new ExternalCategory(categoria.id(), categoria.nome()))
                .toList();
    }

    static ExternalCharge toCharge(ContaAzulApiClient.ContaAReceber conta) {
        return new ExternalCharge(
                conta.id(),
                conta.cliente() == null ? null : conta.cliente().id(),
                conta.descricao(),
                parseDate(conta.dataCompetencia()),
                parseDate(conta.dataVencimento()),
                conta.total(),
                conta.pago(),
                conta.naoPago(),
                mapStatus(conta.statusTraduzido() != null ? conta.statusTraduzido() : conta.status()));
    }

    /** Conta Azul receivable status → Taxify's neutral one. */
    static ChargeStatus mapStatus(String status) {
        if (status == null) {
            return ChargeStatus.OPEN;
        }
        return switch (status.trim().toUpperCase()) {
            case "RECEBIDO", "ACQUITTED" -> ChargeStatus.PAID;
            case "RECEBIDO_PARCIAL", "PARTIAL" -> ChargeStatus.PARTIALLY_PAID;
            case "ATRASADO", "OVERDUE" -> ChargeStatus.OVERDUE;
            case "RENEGOCIADO", "RENEGOTIATED" -> ChargeStatus.RENEGOTIATED;
            case "PERDIDO", "LOST", "CANCELED", "CANCELADO" -> ChargeStatus.CANCELED;
            default -> ChargeStatus.OPEN;
        };
    }

    /** "2026-11-20", "2026-11-20T10:00:00Z"... - the date part is what matters. */
    static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
