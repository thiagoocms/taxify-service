package br.com.cshub.taxify.security;

import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.rest.BillingResource;
import br.com.cshub.taxify.rest.CompanyResource;
import br.com.cshub.taxify.rest.FiscalXmlResource;
import br.com.cshub.taxify.rest.SubscriptionResource;
import br.com.cshub.taxify.rest.UserResource;
import br.com.cshub.taxify.security.PermissionAuthorizationManager.Requirement;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionAuthorizationManagerTest {

    /**
     * Endpoints any logged-in user may call on purpose (the firm, the team list, integration
     * status...). Anything else without @RequiresPermission / @AdminOnly fails the guard test -
     * a new endpoint has to state its rule or be added here consciously.
     */
    private static final Set<String> OPEN_TO_LOGGED_IN_USERS = Set.of(
            "AuthResource#auth",
            "UserResource#getAll", "UserResource#getById", "UserResource#create",
            "UserCompanyResource#getAll", "UserCompanyResource#getById",
            "AccountingFirmResource#getAll", "AccountingFirmResource#getById",
            "FirmIntegrationResource#list",
            // the accountant's e-CPF status - open to the firm's users on purpose (see its Javadoc)
            "FirmCertificateResource#status",
            // the logged-in user's own firm access situation (F1-RN-07) - every user sees the banners
            "FirmAccessResource#current",
            // public signup: anonymous on purpose, see SecurityConfig (F1-RN-01, F1-RN-03, F1-RN-11)
            "SignupResource#config", "SignupResource#signup", "SignupResource#verify", "SignupResource#resend",
            // "Esqueci a senha": anonymous on purpose, see SecurityConfig (SN-RN-01 a SN-RN-06, SN-RN-14)
            "PasswordResetResource#config", "PasswordResetResource#forgot", "PasswordResetResource#check",
            "PasswordResetResource#reset",
            // the logged-in user's own account: password change and e-mail notices (SN-RN-09, AV-RN-08) - any profile
            "MyAccountResource#changePassword", "MyAccountResource#notificationSettings",
            "MyAccountResource#updateNotificationSettings");

    /**
     * F1-RN-08 / CA1-10: every POST/PUT/PATCH/DELETE is a write - blocked for a read-only firm - unless it is one of
     * these, reviewed one by one: POSTs that only read (@ReadOnlyOperation) and the routes always allowed
     * (@AllowedWhenBlocked, F1-RN-09). A new one fails the guard until it is reviewed and added here.
     */
    private static final Set<String> WRITES_ALLOWED_WHEN_BLOCKED = Set.of(
            // @ReadOnlyOperation
            "FiscalXmlResource#buscarXmls", "FiscalXmlResource#totais",
            "FiscalXmlResource#exportarXmls", "FiscalXmlResource#exportarXmlsZip",
            "FronteiraResource#fetchEfiscoMemory", "FronteiraResource#check",
            "ServiceInvoiceResource#search", "ServiceInvoiceResource#totals",
            // @AllowedWhenBlocked
            "AuthResource#auth",
            "SignupResource#signup", "SignupResource#verify", "SignupResource#resend",
            "PlatformFirmResource#apply", "PlatformFirmResource#updateContact",
            // @AllowedWhenBlocked - /platform/** is always allowed for the platform admin (F1-RN-09); capture panel CP-RN-16
            "PlatformCapturePanelResource#syncNow",
            // @AllowedWhenBlocked - password is a credential, the notice a personal preference (P-SN-05, AV-RN-08)
            "PasswordResetResource#forgot", "PasswordResetResource#check", "PasswordResetResource#reset",
            "MyAccountResource#changePassword", "MyAccountResource#updateNotificationSettings");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ rules declared on real controllers

    @Test
    void classRuleSplitsReadsAndWrites() {
        assertThat(rule(CompanyResource.class, "getAll")).contains(Requirement.anyOf(Permission.COMPANIES_VIEW));
        assertThat(rule(CompanyResource.class, "create")).contains(Requirement.admin());
        assertThat(rule(CompanyResource.class, "delete")).contains(Requirement.admin());

        assertThat(rule(SubscriptionResource.class, "getById")).contains(Requirement.anyOf(Permission.SUBSCRIPTIONS_VIEW));
        assertThat(rule(SubscriptionResource.class, "update")).contains(Requirement.anyOf(Permission.SUBSCRIPTIONS_MANAGE));
    }

    @Test
    void methodRuleBeatsTheClassRule() {
        assertThat(rule(FiscalXmlResource.class, "buscarXmls")).as("POST search is a read")
                .contains(Requirement.anyOf(Permission.INVOICES_VIEW));
        assertThat(rule(FiscalXmlResource.class, "sincronizarComSieg")).contains(Requirement.anyOf(Permission.INVOICES_SYNC));
        assertThat(rule(FiscalXmlResource.class, "retificar")).contains(Requirement.anyOf(Permission.INVOICES_RECTIFY));

        assertThat(rule(BillingResource.class, "companyBilling")).contains(Requirement.anyOf(Permission.BILLING_VIEW));
        assertThat(rule(BillingResource.class, "startSync")).contains(Requirement.anyOf(Permission.BILLING_SYNC));
        assertThat(rule(BillingResource.class, "updateSettings")).contains(Requirement.admin());

        assertThat(rule(UserResource.class, "update")).contains(Requirement.admin());
        assertThat(rule(UserResource.class, "getAll")).isEmpty();
    }

    /** Guard: every endpoint either declares its rule on the controller or is consciously open. */
    @Test
    void noEndpointIsLeftWithoutAnAccessRule() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> unguarded = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("br.com.cshub.taxify.rest")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getMethods()) {
                boolean endpoint = AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                        && !Modifier.isStatic(method.getModifiers()) && !method.isBridge();
                String name = controller.getSimpleName() + "#" + method.getName();
                if (endpoint && PermissionAuthorizationManager.requirementFor(method, controller).isEmpty()
                        && !OPEN_TO_LOGGED_IN_USERS.contains(name)) {
                    unguarded.add(name);
                }
            }
        }
        assertThat(unguarded).as("endpoints without @RequiresPermission / @AdminOnly").isEmpty();
    }

    /** Guard: the list of writes that still go through for a read-only firm is exactly the reviewed one. */
    @Test
    void everyWriteIsBlockedForAReadOnlyFirmUnlessReviewed() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> allowed = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("br.com.cshub.taxify.rest")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getMethods()) {
                boolean endpoint = AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                        && !Modifier.isStatic(method.getModifiers()) && !method.isBridge();
                if (!endpoint || !PermissionAuthorizationManager.isWrite(method)) {
                    continue;
                }
                if (FirmAccessInterceptor.isAlwaysAllowed("POST", method, controller)) {
                    allowed.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(allowed).as("writes allowed for a read-only firm").containsExactlyInAnyOrderElementsOf(WRITES_ALLOWED_WHEN_BLOCKED);
    }

    /** F1-RN-15: the console is the platform admin's only - a firm's ADMIN gets 403. */
    @Test
    void theConsoleIsForThePlatformAdminOnly() {
        assertThat(rule(br.com.cshub.taxify.rest.PlatformFirmResource.class, "list")).hasValueSatisfying(r -> assertThat(r.platformAdmin()).isTrue());
        assertThat(rule(br.com.cshub.taxify.rest.PlatformFirmResource.class, "apply")).hasValueSatisfying(r -> assertThat(r.platformAdmin()).isTrue());
        assertThat(rule(br.com.cshub.taxify.rest.PlatformFirmResource.class, "events")).hasValueSatisfying(r -> assertThat(r.platformAdmin()).isTrue());
        assertThat(rule(br.com.cshub.taxify.rest.PlatformFirmResource.class, "updateContact")).hasValueSatisfying(r -> assertThat(r.platformAdmin()).isTrue());
        // capture panel (CP-RN-02, CP-RN-16): the platform view is the platform admin's only; the firm view needs
        // INVOICES_SYNC (ADMIN always passes) and its sync-now is the firm ADMIN's only
        for (String endpoint : java.util.List.of("panel", "company", "syncNow")) {
            assertThat(rule(br.com.cshub.taxify.rest.PlatformCapturePanelResource.class, endpoint))
                    .hasValueSatisfying(r -> assertThat(r.platformAdmin()).isTrue());
        }
        assertThat(rule(br.com.cshub.taxify.rest.CapturePanelResource.class, "panel")).contains(Requirement.anyOf(Permission.INVOICES_SYNC));
        assertThat(rule(br.com.cshub.taxify.rest.CapturePanelResource.class, "company")).contains(Requirement.anyOf(Permission.INVOICES_SYNC));
        assertThat(rule(br.com.cshub.taxify.rest.CapturePanelResource.class, "syncNow")).contains(Requirement.admin());

        PlatformController denied = proxied(new PlatformController(), new PermissionAuthorizationManager(auth -> false));
        authenticateAs("ADMIN");
        assertThatThrownBy(denied::console).isInstanceOf(AccessDeniedException.class);

        PlatformController allowedProxy = proxied(new PlatformController(), new PermissionAuthorizationManager(auth -> true));
        assertThat(allowedProxy.console()).isEqualTo("console");
    }

    @PlatformAdminOnly
    static class PlatformController {
        @GetMapping
        public String console() {
            return "console";
        }
    }

    // ------------------------------------------------------------------ decision + interceptor wiring

    @RequiresPermission(value = Permission.INVOICES_VIEW, write = Permission.INVOICES_AUDIT)
    static class SampleController {
        @GetMapping
        public String read() {
            return "read";
        }

        @PostMapping
        public String write() {
            return "write";
        }

        @AdminOnly
        @GetMapping("/admin")
        public String admin() {
            return "admin";
        }
    }

    @Test
    void interceptorAllowsOrDeniesBeforeTheMethodRuns() {
        SampleController proxy = proxied(new SampleController());

        authenticateAs("EMPLOYEE", "INVOICES_VIEW");
        assertThat(proxy.read()).isEqualTo("read");
        assertThatThrownBy(proxy::write).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(proxy::admin).isInstanceOf(AccessDeniedException.class);

        authenticateAs("EMPLOYEE", "INVOICES_AUDIT");
        assertThat(proxy.write()).isEqualTo("write");

        authenticateAs("ADMIN");
        assertThat(proxy.read()).isEqualTo("read");
        assertThat(proxy.write()).isEqualTo("write");
        assertThat(proxy.admin()).isEqualTo("admin");
    }

    @Test
    void userWithoutPermissionsIsDenied() {
        SampleController proxy = proxied(new SampleController());

        authenticateAs("EMPLOYEE");

        assertThatThrownBy(proxy::read).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<Requirement> rule(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getMethods())
                .filter(m -> m.getName().equals(methodName) && !m.isBridge())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(controller.getSimpleName() + " has no " + methodName));
        return PermissionAuthorizationManager.requirementFor(method, controller);
    }

    private static <T> T proxied(T target) {
        return proxied(target, new PermissionAuthorizationManager());
    }

    private static <T> T proxied(T target, PermissionAuthorizationManager manager) {
        MethodInterceptor interceptor = AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    private static void authenticateAs(String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("user", null, authorities);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
