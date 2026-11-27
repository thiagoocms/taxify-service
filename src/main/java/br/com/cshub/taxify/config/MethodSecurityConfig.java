package br.com.cshub.taxify.config;

import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.PlatformAdminOnly;
import br.com.cshub.taxify.security.PermissionAuthorizationManager;
import br.com.cshub.taxify.security.RequiresPermission;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
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
 * Turns {@link RequiresPermission} / {@link AdminOnly} / {@link PlatformAdminOnly} on controllers into a check before the
 * method runs (403 when it fails, see GlobalExceptionHandler) - each controller declares its
 * own access rule instead of one long list in SecurityConfig.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor permissionAuthorizationAdvisor(ObjectProvider<PlatformAdminService> platformAdminService) {
        // the platform admin is read from the database on each call (login, profile and firm), never from the token
        PermissionAuthorizationManager manager = new PermissionAuthorizationManager(
                authentication -> platformAdminService.getObject().isCurrentUserPlatformAdmin());
        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(annotatedEndpoints(), manager);
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }

    /** Any method of a class annotated with either annotation, or any method annotated itself. */
    private static Pointcut annotatedEndpoints() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(AdminOnly.class, true))
                .union(new AnnotationMatchingPointcut(null, RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(null, AdminOnly.class, true))
                .union(new AnnotationMatchingPointcut(PlatformAdminOnly.class, true))
                .union(new AnnotationMatchingPointcut(null, PlatformAdminOnly.class, true));
    }
}
