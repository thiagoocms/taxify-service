package br.com.cshub.taxify.config;

import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.PermissionAuthorizationManager;
import br.com.cshub.taxify.security.RequiresPermission;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns {@link RequiresPermission} / {@link AdminOnly} on controllers into a check before the
 * method runs (403 when it fails, see GlobalExceptionHandler) - each controller declares its
 * own access rule instead of one long list in SecurityConfig.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor permissionAuthorizationAdvisor() {
        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(annotatedEndpoints(), new PermissionAuthorizationManager());
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }

    /** Any method of a class annotated with either annotation, or any method annotated itself. */
    private static Pointcut annotatedEndpoints() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(AdminOnly.class, true))
                .union(new AnnotationMatchingPointcut(null, RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(null, AdminOnly.class, true));
    }
}
