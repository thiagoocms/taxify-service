package br.com.cshub.taxify.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Um POST que só lê (busca com filtro no corpo, totais, exportação): continua liberado para o escritório em somente
 * leitura (F1-RN-08). Todo POST/PUT/PATCH/DELETE sem esta anotação é tratado como escrita - o teste-guarda lista os
 * anotados para a revisão.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReadOnlyOperation {
}
