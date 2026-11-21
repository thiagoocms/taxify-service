package br.com.cshub.taxify.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint reserved to the firm's ADMIN (users, firm settings, integrations, the registry
 * catalog). On a class with {@link #writeOnly()} only its write endpoints are reserved - reads
 * follow the class's {@link RequiresPermission}, or just need a logged-in user.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface AdminOnly {

    /** Class-level only: reserve just POST/PUT/PATCH/DELETE. */
    boolean writeOnly() default false;
}
