package br.com.cshub.taxify.security;

import br.com.cshub.taxify.enumerated.Permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Permission an endpoint requires - declared on the controller itself, checked before the
 * method runs by {@link PermissionAuthorizationManager}. ADMIN always passes; otherwise the
 * user needs ANY of the listed permissions (403 when not).
 * <p>
 * On a method it applies to that endpoint. On a class it applies to all of the controller's
 * endpoints, including the inherited CRUD ones - {@link #value()} for reads (GET) and
 * {@link #write()} for POST/PUT/PATCH/DELETE (falling back to {@link #value()} when empty).
 * A method annotation, or {@link AdminOnly}, beats the class one.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RequiresPermission {

    /** Any of these. */
    Permission[] value();

    /** Class-level only: any of these for the write endpoints; empty = same as {@link #value()}. */
    Permission[] write() default {};
}
