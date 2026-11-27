package br.com.cshub.taxify.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rotas sempre liberadas, mesmo com o escritório bloqueado (F1-RN-09): o login, a situação de acesso, o cadastro e o
 * console da plataforma (que só o admin da plataforma alcança). Na fase 2, a cobrança do Taxify.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AllowedWhenBlocked {
}
