package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.PartyAddress;
import br.com.cshub.taxify.support.TestNotes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyRegistrationServiceTest {

    private static GoodsInvoice ownNote() {
        return TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).ownIssue()
                .issuerAddress(new PartyAddress("Rua das Flores", "100", null, "Centro", "Caruaru", "55.000-000", null, null, null))
                .issuerCity("2604106").issuerUf("PE").issuerCnae("4781-4/00").issuerStateRegistration("0123456-78")
                .issuerMunicipalRegistration("998877").build();
    }

    @Test
    void fillsOnlyWhatIsEmpty() {
        Company company = new Company(UUID.randomUUID());
        company.setAddressStreet("Avenida já cadastrada");

        boolean changed = CompanyRegistrationService.fill(company, ownNote());

        assertThat(changed).isTrue();
        assertThat(company.getAddressStreet()).isEqualTo("Avenida já cadastrada");
        assertThat(company.getAddressCity()).isEqualTo("Caruaru");
        assertThat(company.getAddressCityCode()).isEqualTo("2604106");
        assertThat(company.getAddressZipCode()).isEqualTo("55000000");
        assertThat(company.getMainCnae()).isEqualTo("4781400");
        assertThat(company.getMunicipalRegistration()).isEqualTo("998877");
        assertThat(company.getStateRegistration()).isEqualTo("012345678/PE");
    }

    @Test
    void keepsAnIeAlreadyRegisteredForTheUf() {
        Company company = new Company(UUID.randomUUID());
        company.replaceStateRegistrations(List.of(new StateRegistration("PE", "111")));

        CompanyRegistrationService.fill(company, ownNote());

        assertThat(company.getStateRegistration()).isEqualTo("111/PE");
    }

    @Test
    void stateRegistrationsRoundTripAsText() {
        assertThat(StateRegistration.parse("0123456-78/pe; ISENTO/SP; semuf")).extracting(StateRegistration::getUf, StateRegistration::getNumber)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PE", "012345678"), org.assertj.core.groups.Tuple.tuple("SP", "ISENTO"));
        assertThat(StateRegistration.format(StateRegistration.parse("1/PE;2/SP"))).isEqualTo("1/PE; 2/SP");
        assertThat(StateRegistration.format(List.of())).isNull();
    }
}
