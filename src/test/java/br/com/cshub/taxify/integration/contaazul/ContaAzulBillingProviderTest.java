package br.com.cshub.taxify.integration.contaazul;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.integration.billing.ExternalCharge;
import br.com.cshub.taxify.integration.billing.ExternalContract;
import br.com.cshub.taxify.service.ContaAzulAuthService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContaAzulBillingProviderTest {

    private final ContaAzulApiClient client = mock(ContaAzulApiClient.class);
    private final ContaAzulBillingProvider provider = new ContaAzulBillingProvider(client, mock(ContaAzulAuthService.class));
    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());

    @Test
    void mapsEveryReceivableStatus() {
        assertThat(ContaAzulBillingProvider.mapStatus("RECEBIDO")).isEqualTo(ChargeStatus.PAID);
        assertThat(ContaAzulBillingProvider.mapStatus("RECEBIDO_PARCIAL")).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(ContaAzulBillingProvider.mapStatus("ATRASADO")).isEqualTo(ChargeStatus.OVERDUE);
        assertThat(ContaAzulBillingProvider.mapStatus("RENEGOCIADO")).isEqualTo(ChargeStatus.RENEGOTIATED);
        assertThat(ContaAzulBillingProvider.mapStatus("PERDIDO")).isEqualTo(ChargeStatus.CANCELED);
        assertThat(ContaAzulBillingProvider.mapStatus("EM_ABERTO")).isEqualTo(ChargeStatus.OPEN);
        assertThat(ContaAzulBillingProvider.mapStatus(null)).isEqualTo(ChargeStatus.OPEN);
    }

    @Test
    void findsCustomerWithMaskedDocumentWhenDigitsDontMatch() {
        when(client.findPessoasByDocument(firm.getId(), "11594485000105")).thenReturn(List.of());
        when(client.findPessoasByDocument(firm.getId(), "11.594.485/0001-05")).thenReturn(List.of(
                new ContaAzulApiClient.Pessoa("p1", "Cliente", "11.594.485/0001-05", true)));

        assertThat(provider.findCustomerByDocument(firm, "11.594.485/0001-05"))
                .hasValueSatisfying(customer -> assertThat(customer.id()).isEqualTo("p1"));
    }

    @Test
    void contractStatusAndDatesAreTranslated() {
        when(client.listContratos(eq(firm.getId()), eq("c1"), anyString(), anyString())).thenReturn(List.of(
                new ContaAzulApiClient.Contrato("k1", new ContaAzulApiClient.Ref("c1", "Cliente"), "ATIVO",
                        "2026-12-10", "2025-01-01T00:00:00Z", 42)));

        ExternalContract contract = provider.listContracts(firm, "c1").getFirst();

        assertThat(contract.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(contract.number()).isEqualTo("42");
        assertThat(contract.startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(contract.nextDueDate()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(contract.amount()).isNull();
    }

    @Test
    void dueRangeLongerThan365DaysIsSplitIntoWindowsThatFit() {
        LocalDate from = LocalDate.of(2025, 10, 1);
        LocalDate to = LocalDate.of(2027, 2, 20);

        List<LocalDate[]> windows = ContaAzulBillingProvider.dueWindows(from, to);

        assertThat(windows).hasSize(2);
        assertThat(windows.getFirst()[0]).isEqualTo(from);
        assertThat(windows.getLast()[1]).isEqualTo(to);
        for (int i = 0; i < windows.size(); i++) {
            LocalDate[] window = windows.get(i);
            long days = java.time.temporal.ChronoUnit.DAYS.between(window[0], window[1]) + 1;
            assertThat(days).isBetween(1L, (long) ContaAzulBillingProvider.MAX_DUE_RANGE_DAYS);
            if (i > 0) {
                assertThat(window[0]).isEqualTo(windows.get(i - 1)[1].plusDays(1));
            }
        }
        assertThat(ContaAzulBillingProvider.dueWindows(from, from)).hasSize(1);
    }

    @Test
    void listChargesQueriesEachWindowAndDedupes() {
        when(client.searchContasAReceber(eq(firm.getId()), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(new ContaAzulApiClient.ContaAReceber("r1", "Honorários", "2026-01-10", null, null, "EM_ABERTO",
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, List.of(), new ContaAzulApiClient.Ref("c1", "Cliente"))));

        List<ExternalCharge> charges = provider.listCharges(firm, new br.com.cshub.taxify.integration.billing.ChargeQuery(
                List.of("c1"), List.of(), LocalDate.of(2025, 10, 1), LocalDate.of(2027, 2, 20), null));

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).searchContasAReceber(eq(firm.getId()),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(charges).hasSize(1);
    }

    @Test
    void retryDelayHonorsRetryAfterAndBacksOffOtherwise() {
        assertThat(ContaAzulApiClient.retryDelay("5", 1)).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(ContaAzulApiClient.retryDelay("999", 1)).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(ContaAzulApiClient.retryDelay(null, 1)).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(ContaAzulApiClient.retryDelay(null, 3)).isEqualTo(java.time.Duration.ofSeconds(8));
        assertThat(ContaAzulApiClient.retryDelay("Wed, 21 Oct 2026 07:28:00 GMT", 2)).isEqualTo(java.time.Duration.ofSeconds(4));
        assertThat(ContaAzulApiClient.retryDelay(null, 10)).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void receivableBecomesCharge() {
        ExternalCharge charge = ContaAzulBillingProvider.toCharge(new ContaAzulApiClient.ContaAReceber(
                "r1", "Honorários", "2026-11-10", "2026-11-01", "X", "RECEBIDO_PARCIAL",
                new BigDecimal("900"), new BigDecimal("400"), new BigDecimal("500"), List.of(),
                new ContaAzulApiClient.Ref("c1", "Cliente")));

        assertThat(charge.customerId()).isEqualTo("c1");
        assertThat(charge.status()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(charge.dueDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(charge.openAmount()).isEqualByComparingTo("400");
        assertThat(charge.paidAmount()).isEqualByComparingTo("500");
    }
}
