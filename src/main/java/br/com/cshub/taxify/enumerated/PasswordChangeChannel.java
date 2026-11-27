package br.com.cshub.taxify.enumerated;

/** Por onde a senha mudou (SN-RN-12) - escolhe o texto do aviso de senha alterada e o log. */
public enum PasswordChangeChannel {
    /** Redefinição pelo link do e-mail (SN-RN-06). */
    RESET,
    /** O próprio usuário em Minha conta (SN-RN-09). */
    SELF,
    /** O ADMIN do escritório pela tela de usuários (SN-RN-10). */
    ADMIN
}
