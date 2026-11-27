package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.dto.firmaccess.FirmContactUpdateDTO;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.BusinessException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessActions;
import br.com.cshub.taxify.service.firmaccess.SignupRules;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Validações de entrada do console da plataforma (F1-RN-13, F1-RN-14): o filtro de situação, a ação pedida, o
 * escritório isento e o contato do escritório (mesmas validações e códigos do cadastro). As regras das ações em si
 * (o que cada uma aceita na situação atual) são puras, em FirmAccessActions.
 */
@Component
public class PlatformFirmValidation {

    private final MessageService messageService;
    private final AccountingFirmRepository firmRepository;

    public PlatformFirmValidation(MessageService messageService, AccountingFirmRepository firmRepository) {
        this.messageService = messageService;
        this.firmRepository = firmRepository;
    }

    /** O contato pedido, limpo: campo nulo = mantém o atual; o CNPJ só com dígitos e o e-mail em minúsculas. */
    public record ContactChange(String documentNumber, String billingEmail, String contactPhone) {
    }

    /** {@code ?status=} da lista: vazio = todas; desconhecido = 400 {@code error.platform.status.invalid}. */
    public FirmSubscriptionStatus checkStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FirmSubscriptionStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.platform.status.invalid",
                    messageService.getMessage("error.platform.status.invalid", value));
        }
    }

    /** A ação do caminho ({@code extend-trial}, {@code release}...); outra qualquer é 404, como uma rota inexistente. */
    public FirmAccessActions.Action checkAction(String path) {
        FirmAccessActions.Action action = FirmAccessActions.Action.fromPath(path);
        if (action == null) {
            throw new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found"));
        }
        return action;
    }

    /** O escritório da CS Hub não tem teste, bloqueio nem limite (F1-RN-12): só a revisão se aplica (409). */
    public void checkNotExempt(boolean exempt, FirmAccessActions.Action action) {
        if (exempt && action != FirmAccessActions.Action.MARK_REVIEWED) {
            throw conflict("error.platform.firm.exempt");
        }
    }

    /** A recusa da regra da ação: 400 (motivo ou data faltando, data no passado) ou 409 (não cabe na situação atual). */
    public BusinessException refused(FirmAccessActions.Refused refused) {
        return refused.badRequest()
                ? new BadRequestException(refused.code(), messageService.getMessage(refused.code()))
                : conflict(refused.code());
    }

    /**
     * F1-RN-13: o corpo de {@code PUT /contact}, na ordem CNPJ, e-mail financeiro e telefone - os mesmos códigos do
     * cadastro. Sem nenhum dos três é 400.
     */
    public ContactChange checkContact(FirmContactUpdateDTO request) {
        if (request == null || (request.documentNumber() == null && request.billingEmail() == null && request.contactPhone() == null)) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
        if (request.documentNumber() != null && !SignupRules.isValidCnpj(request.documentNumber())) {
            throw badRequest("error.signup.firm.document.invalid");
        }
        if (request.billingEmail() != null && !SignupRules.isValidEmail(request.billingEmail().strip())) {
            throw badRequest("error.signup.billing.email.invalid");
        }
        String phone = request.contactPhone() == null ? null : SignupRules.normalizePhone(request.contactPhone());
        if (request.contactPhone() != null && phone == null) {
            throw badRequest("error.signup.phone.invalid");
        }
        return new ContactChange(
                request.documentNumber() == null ? null : DocumentNumbers.onlyDigits(request.documentNumber()),
                request.billingEmail() == null ? null : request.billingEmail().strip().toLowerCase(Locale.ROOT),
                phone);
    }

    /** F1-RN-03: um cadastro não verificado é expurgado com os dados pessoais - nada dele entra na trilha (409). */
    public void checkContactEditable(FirmSubscription subscription) {
        if (subscription != null && subscription.getStatus() == FirmSubscriptionStatus.PENDING_VERIFICATION) {
            throw conflict("error.platform.action.invalid");
        }
    }

    /** O CNPJ é único entre os escritórios, sem contar o próprio (409 {@code error.signup.firm.document.exists}). */
    public void checkDocumentAvailable(String documentNumber, String currentDocumentNumber, UUID firmId) {
        if (documentNumber != null && !documentNumber.equals(currentDocumentNumber)
                && firmRepository.existsByDocumentNumberAndIdNot(documentNumber, firmId)) {
            throw documentTaken();
        }
    }

    /** Outro escritório gravou o mesmo CNPJ ao mesmo tempo - a chave única segurou. */
    public ConflictException documentTaken() {
        return conflict("error.signup.firm.document.exists");
    }

    private BadRequestException badRequest(String code) {
        return new BadRequestException(code, messageService.getMessage(code));
    }

    private ConflictException conflict(String code) {
        return new ConflictException(code, messageService.getMessage(code));
    }
}
