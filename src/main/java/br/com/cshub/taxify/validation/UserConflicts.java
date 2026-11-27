package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.ConflictException;

/**
 * Os 409 de duplicidade de usuário, com código para o front, iguais em {@code POST /signup} e {@code POST /users}.
 * O login reservado da CS Hub (PLATFORM_ADMIN_LOGINS) recebe exatamente a mesma resposta do login em uso (F1-RN-04).
 */
final class UserConflicts {

    static final String LOGIN_EXISTS = "error.user.login.already.exists";
    static final String DOCUMENT_EXISTS = "error.user.document.already.exists";

    private UserConflicts() {
    }

    static ConflictException loginTaken(MessageService messages) {
        return new ConflictException(LOGIN_EXISTS, messages.getMessage(LOGIN_EXISTS));
    }

    /** CPF já cadastrado - a mensagem continua a de documento repetido. */
    static ConflictException documentTaken(MessageService messages) {
        return new ConflictException(DOCUMENT_EXISTS, messages.getMessage("error.document.exist"));
    }
}
