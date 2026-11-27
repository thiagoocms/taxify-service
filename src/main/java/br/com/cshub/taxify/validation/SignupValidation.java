package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.dto.signup.SignupFirmDTO;
import br.com.cshub.taxify.dto.signup.SignupOwnerDTO;
import br.com.cshub.taxify.dto.signup.SignupRequestDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.firmaccess.SignupRules;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Validações de entrada do cadastro público (F1-RN-01, F1-RN-02, F1-RN-04, F1-RN-11), na ordem em que o cadastro as
 * aplica: {@link #checkRequest} (corpo e versão dos termos), {@link #checkFields} (cada campo, na ordem do formulário -
 * o primeiro inválido responde) e {@link #checkRelations} (login, CPF e CNPJ já usados). Entre as duas últimas o
 * SignupService confere o limite por IP (F1-RN-06).
 */
@Component
public class SignupValidation {

    /** Column limits: accounting_firms.name / users.name (150), users.login (50); e-mails 300 in SignupRules. */
    static final int NAME_MAX = 150;
    static final int LOGIN_MAX = 50;
    /** BCrypt only takes 72 bytes (Spring refuses longer ones with an exception). */
    static final int PASSWORD_MAX_BYTES = 72;
    private static final int CPF_LENGTH = 11;

    private final MessageService messageService;
    private final SaasProperties properties;
    private final PlatformAdminService platformAdminService;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final AccountingFirmRepository firmRepository;

    public SignupValidation(MessageService messageService, SaasProperties properties, PlatformAdminService platformAdminService,
                            UserRepository userRepository, DocumentRepository documentRepository,
                            AccountingFirmRepository firmRepository) {
        this.messageService = messageService;
        this.properties = properties;
        this.platformAdminService = platformAdminService;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.firmRepository = firmRepository;
    }

    /** O pedido limpo: CNPJ, CPF e telefone só com dígitos, e-mail financeiro em minúsculas. */
    public record SignupData(String firmName, String cnpj, String billingEmail, String phone,
                             String userName, String cpf, String login, String email, String password) {
    }

    /** O corpo e os termos: 400 sem corpo ou sem termos; 409 {@code error.signup.terms.outdated} com outra versão. */
    public String checkRequest(SignupRequestDTO request) {
        if (request == null || request.firm() == null || request.user() == null) {
            throw badRequest("error.generic.bad.request");
        }
        String terms = request.termsVersion() == null ? "" : request.termsVersion().strip();
        if (terms.isEmpty()) {
            throw badRequest("error.signup.terms.required");
        }
        if (!terms.equals(properties.getTermsVersion())) {
            throw new ConflictException("error.signup.terms.outdated", messageService.getMessage("error.signup.terms.outdated"));
        }
        return terms;
    }

    /** Cada campo, na ordem do formulário: obrigatório, depois o formato (400 com o código do campo). */
    public SignupData checkFields(SignupRequestDTO request) {
        SignupFirmDTO firm = request.firm();
        SignupOwnerDTO owner = request.user();
        String firmName = required(firm.name(), "firm.name");
        if (firmName.length() > NAME_MAX) {
            throw badRequest("error.signup.firm.name.invalid");
        }
        if (!SignupRules.isValidCnpj(firm.documentNumber())) {
            throw badRequest("error.signup.firm.document.invalid");
        }
        String billingEmail = firm.billingEmail() == null ? null : firm.billingEmail().strip();
        if (!SignupRules.isValidEmail(billingEmail)) {
            throw badRequest("error.signup.billing.email.invalid");
        }
        String phone = SignupRules.normalizePhone(firm.contactPhone());
        if (phone == null) {
            throw badRequest("error.signup.phone.invalid");
        }
        String userName = required(owner.name(), "user.name");
        if (userName.length() > NAME_MAX) {
            throw badRequest("error.signup.user.name.invalid");
        }
        String cpf = DocumentNumbers.onlyDigits(required(owner.document(), "user.document"));
        if (cpf.length() != CPF_LENGTH) {
            throw badRequest("error.signup.user.document.invalid");
        }
        String login = required(owner.login(), "user.login");
        if (login.length() > LOGIN_MAX) {
            throw badRequest("error.signup.login.invalid");
        }
        String email = required(owner.email(), "user.email");
        if (!SignupRules.isValidEmail(email)) {
            throw badRequest("error.signup.email.invalid");
        }
        if (owner.password() == null || owner.password().isBlank()) {
            throw badRequest("error.user.password.required");
        }
        if (owner.password().getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw badRequest("error.signup.password.invalid");
        }
        return new SignupData(firmName, DocumentNumbers.onlyDigits(firm.documentNumber()),
                billingEmail.toLowerCase(Locale.ROOT), phone, userName, cpf, login, email, owner.password());
    }

    /**
     * Login, CPF e CNPJ livres (409). F1-RN-04: um login da lista da CS Hub responde igual a login em uso, para não
     * revelar a lista (uk_users_login vale também para os excluídos). F1-RN-02: o CNPJ é único só entre os escritórios
     * (excluídos e não verificados contam); empresa cliente não esbarra.
     */
    public void checkRelations(SignupData data) {
        if (platformAdminService.isReservedLogin(data.login()) || userRepository.existsByLogin(data.login())) {
            throw UserConflicts.loginTaken(messageService);
        }
        if (documentRepository.existsByNumberAndTypeNative(data.cpf(), DocumentTypeEnum.CPF)) {
            throw UserConflicts.documentTaken(messageService);
        }
        if (firmRepository.existsByDocumentNumber(data.cnpj())) {
            throw new ConflictException("error.signup.firm.document.exists",
                    messageService.getMessage("error.signup.firm.document.exists"));
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("error.mandatory.fields", messageService.getMessage("error.mandatory.fields", field));
        }
        return value.strip();
    }

    private BadRequestException badRequest(String code) {
        return new BadRequestException(code, messageService.getMessage(code));
    }
}
