package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.BillingChargeMapper;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingChargeValidationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    private final BillingChargeRepository repository = mock(BillingChargeRepository.class);
    private final MessageService messageService = mock(MessageService.class);
    private final BillingChargeValidation validation = new BillingChargeValidation(repository, messageService, new BillingChargeMapper(),
            mock(CompanyValidation.class), mock(SubscriptionValidation.class), mock(TenantAccessService.class));

    private static BillingCharge charge(String amount, String paid, LocalDate due) {
        BillingCharge charge = new BillingCharge();
        charge.setAmount(new BigDecimal(amount));
        charge.setPaidAmount(paid == null ? null : new BigDecimal(paid));
        charge.setOpenAmount(new BigDecimal(amount).subtract(paid == null ? BigDecimal.ZERO : new BigDecimal(paid)));
        charge.setDueDate(due);
        return charge;
    }

    @Test
    void statusFollowsAmountsAndDueDate() {
        assertThat(BillingChargeValidation.statusOf(charge("500", "0", TODAY.plusDays(5)), TODAY)).isEqualTo(ChargeStatus.OPEN);
        assertThat(BillingChargeValidation.statusOf(charge("500", "200", TODAY.plusDays(5)), TODAY)).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(BillingChargeValidation.statusOf(charge("500", "500", TODAY.minusDays(5)), TODAY)).isEqualTo(ChargeStatus.PAID);
        assertThat(BillingChargeValidation.statusOf(charge("500", "200", TODAY.minusDays(1)), TODAY)).isEqualTo(ChargeStatus.OVERDUE);

        BillingCharge canceled = charge("500", "0", TODAY.minusDays(1));
        canceled.setStatus(ChargeStatus.CANCELED);
        assertThat(BillingChargeValidation.statusOf(canceled, TODAY)).isEqualTo(ChargeStatus.CANCELED);
    }

    @Test
    void aNewChargeIsAlwaysManual() {
        BillingCharge charge = charge("500", null, TODAY);
        charge.setSource(BillingProviderType.CONTA_AZUL);
        charge.setExternalId("abc");

        validation.checkOwnerFieldsToCreate(charge);

        assertThat(charge.getSource()).isEqualTo(BillingProviderType.MANUAL);
        assertThat(charge.getExternalId()).isNull();
    }

    @Test
    void aSyncedChargeCannotBeEdited() {
        UUID id = UUID.randomUUID();
        BillingCharge synced = charge("500", null, TODAY);
        synced.setId(id);
        synced.setCompany(new Company(UUID.randomUUID()));
        synced.setSource(BillingProviderType.CONTA_AZUL);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(synced));
        when(messageService.getMessage(anyString())).thenReturn("msg");
        BillingCharge edit = charge("100", null, TODAY);
        edit.setId(id);

        assertThatThrownBy(() -> validation.checkUpdateConsistence(id, edit)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void paidCannotExceedTheAmount() {
        when(messageService.getMessage(anyString())).thenReturn("msg");

        assertThatThrownBy(() -> validation.checkValidationsExtras(charge("500", "600", TODAY))).isInstanceOf(BadRequestException.class);
    }
}
