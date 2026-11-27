package br.com.cshub.taxify.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint só do admin da plataforma (a CS Hub - ver PlatformAdminService): o console que cruza escritórios
 * ({@code /platform/**}). O ADMIN de um escritório comum recebe 403. Conferido a cada chamada no banco, não pelo claim
 * do token (F1-RN-15). Vale sobre qualquer outra anotação.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface PlatformAdminOnly {
}
