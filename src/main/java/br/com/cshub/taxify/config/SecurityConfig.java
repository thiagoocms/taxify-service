package br.com.cshub.taxify.config;

import br.com.cshub.taxify.security.SecurityErrorHandler;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${jwt.key.public}")
    private RSAPublicKey key;
    @Value("${jwt.key.private}")
    private RSAPrivateKey priv;

    /** Authority of the ADMIN user type - firm administration and every permission. */
    private static final String ADMIN = UserProfileEnum.ADMIN.name();

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityErrorHandler securityErrors) throws Exception {
        String base = AppConstants.API + AppConstants.V1;
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> auth
                                .requestMatchers(HttpMethod.POST, base + "/auth").permitAll()
                                // Public signup of a new firm (its owner is always ADMIN), the e-mail verification
                                // and its resend, and whether signup is open - see SignupResource (F1-RN-01).
                                .requestMatchers(HttpMethod.POST, base + "/signup", base + "/signup/verify",
                                        base + "/signup/resend").permitAll()
                                .requestMatchers(HttpMethod.GET, base + "/signup/config").permitAll()
                                // Only a logged-in ADMIN adds a teammate - the public signup is POST /signup now
                                // (F1-RN-01): POST /users is no longer anonymous.
                                .requestMatchers(HttpMethod.POST, base + "/users")
                                .access(AuthorityAuthorizationManager.hasAuthority(ADMIN))
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                                // Actuator health - see application.yaml's management.endpoints comment
                                // for why it's the only one exposed.
                                .requestMatchers("/actuator/health").permitAll()
                                // Everything else needs a logged-in user. What each endpoint further requires
                                // (a permission, or ADMIN) is declared on the controller itself with
                                // @RequiresPermission / @AdminOnly - see MethodSecurityConfig.
                                .anyRequest().authenticated()
                )
                // No HTTP Basic: every client logs in through POST /auth and sends the JWT. Basic let any endpoint
                // authenticate with login:password directly, skipping AuthService's checks (e.g. the e-mail not
                // verified yet - F1-RN-03) and open to password guessing on every route.
                .httpBasic(AbstractHttpConfigurer::disable)
                // 401/403 answered by the filters leave in the API's single error body (keeping WWW-Authenticate: Bearer)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(securityErrors).accessDeniedHandler(securityErrors))
                .oauth2ResourceServer(
                        conf -> conf.authenticationEntryPoint(securityErrors).accessDeniedHandler(securityErrors).jwt(
                                jwt -> jwt.decoder(jwtDecoder())
                                        .jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * TokenService puts the user's authorities in a plain "authorities" claim (see
     * Auth#getAuthorities), not the "scope"/"scp" claim Spring Security's default
     * JwtGrantedAuthoritiesConverter looks for - without this, every token would
     * authenticate with zero authorities and every hasAuthority(...) check would 403.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authorities");
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(this.key).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(this.key).privateKey(this.priv).build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
