package br.com.cshub.taxify.config;

import br.com.cshub.taxify.constants.AppConstants;
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
import org.springframework.security.config.Customizer;
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

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String base = AppConstants.API + AppConstants.V1;
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> auth
                                .requestMatchers(HttpMethod.POST, base + "/auth").permitAll()
                                .requestMatchers(HttpMethod.POST, base + "/users").permitAll()
                                    .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                                // Actuator health/prometheus - see application.yaml's management.endpoints
                                // comment for why only these two are exposed.
                                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                                // Manipulação (criar/editar/excluir) de empresas, usuários (e seus
                                // vínculos com empresas), planos, assinaturas e regras fiscais é
                                // restrita a ADMIN - leitura (GET) continua liberada pra qualquer
                                // perfil autenticado (a restrição por escritório já é aplicada em
                                // TenantAccessService, independente de profile).
                                .requestMatchers(HttpMethod.POST, base + "/companies").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/companies/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/companies/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.POST, base + "/users/*/companies").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/users/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/users/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.POST, base + "/plans").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/plans/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/plans/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.POST, base + "/subscriptions").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/subscriptions/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/subscriptions/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.POST, base + "/product-fiscal-rules").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/product-fiscal-rules/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/product-fiscal-rules/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.POST, base + "/accounting-firms").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.PUT, base + "/accounting-firms/**").hasAuthority("ADMIN")
                                .requestMatchers(HttpMethod.DELETE, base + "/accounting-firms/**").hasAuthority("ADMIN")
                                .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .oauth2ResourceServer(
                        conf -> conf.jwt(
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
