package br.com.cshub.taxify.security;

import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Decides {@link RequiresPermission} / {@link AdminOnly} for one controller call. The rule for
 * an endpoint ({@link #requirementFor}) is resolved from the annotations alone, so it can also
 * be inspected without a request - the test that makes sure no endpoint was left unguarded
 * uses exactly that.
 */
public class PermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private static final String ADMIN = UserProfileEnum.ADMIN.name();
    private static final Set<RequestMethod> READ_METHODS = EnumSet.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS);

    /** What an endpoint demands: ADMIN only, or ADMIN / any of {@code permissions}. */
    public record Requirement(boolean adminOnly, Set<Permission> permissions) {

        static Requirement admin() {
            return new Requirement(true, Set.of());
        }

        static Requirement anyOf(Permission... permissions) {
            return new Requirement(false, permissions.length == 0 ? Set.of() : EnumSet.copyOf(Arrays.asList(permissions)));
        }

        boolean isSatisfiedBy(Set<String> authorities) {
            if (authorities.contains(ADMIN)) {
                return true;
            }
            return !adminOnly && permissions.stream().anyMatch(permission -> authorities.contains(permission.name()));
        }
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
        Class<?> controller = AopUtils.getTargetClass(invocation.getThis());
        Optional<Requirement> requirement = requirementFor(invocation.getMethod(), controller);
        if (requirement.isEmpty()) {
            return new AuthorizationDecision(true);
        }
        Authentication current = authentication.get();
        Set<String> authorities = current == null || !current.isAuthenticated()
                ? Set.of()
                : current.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return new AuthorizationDecision(requirement.get().isSatisfiedBy(authorities));
    }

    /**
     * The endpoint's rule: method annotations first, then the class's (read/write split);
     * empty when neither declares one - then being logged in is enough (SecurityConfig).
     */
    public static Optional<Requirement> requirementFor(Method method, Class<?> controller) {
        Method specific = AopUtils.getMostSpecificMethod(method, controller);

        if (AnnotatedElementUtils.hasAnnotation(specific, AdminOnly.class)) {
            return Optional.of(Requirement.admin());
        }
        RequiresPermission onMethod = AnnotatedElementUtils.findMergedAnnotation(specific, RequiresPermission.class);
        if (onMethod != null) {
            return Optional.of(Requirement.anyOf(onMethod.value()));
        }

        boolean write = isWrite(specific);
        AdminOnly adminOnClass = AnnotatedElementUtils.findMergedAnnotation(controller, AdminOnly.class);
        if (adminOnClass != null && (!adminOnClass.writeOnly() || write)) {
            return Optional.of(Requirement.admin());
        }
        RequiresPermission onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequiresPermission.class);
        if (onClass != null) {
            Permission[] permissions = write && onClass.write().length > 0 ? onClass.write() : onClass.value();
            return Optional.of(Requirement.anyOf(permissions));
        }
        return Optional.empty();
    }

    /** POST/PUT/PATCH/DELETE - or a mapping with no method at all, treated as write to stay on the safe side. */
    static boolean isWrite(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null || mapping.method().length == 0) {
            return true;
        }
        return Arrays.stream(mapping.method()).noneMatch(READ_METHODS::contains);
    }
}
