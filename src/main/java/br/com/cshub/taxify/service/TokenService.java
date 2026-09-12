package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.exception.UnauthorizedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public TokenService(JwtEncoder encoder, JwtDecoder decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
    }

    public String generateToken(Auth auth) {
        LocalDateTime now = LocalDateTime.now();
        long expiry = 2L;

        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        var claims = JwtClaimsSet.builder()
                .issuer("taxify-service")
                .issuedAt(now.toInstant(ZoneOffset.of("-03:00")))
                .expiresAt(now.plusHours(expiry).toInstant(ZoneOffset.of("-03:00")))
                .subject(auth.getUsername())
                .claim("authorities", authorities)
                .claim("userId", auth.getId())
                .claim("profile", auth.getProfile().name());


        return encoder.encode(
                        JwtEncoderParameters.from(claims.build()))
                .getTokenValue();
    }

    public void validateToken(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (BadJwtException e) {
            throw new UnauthorizedException("invalid token");
        }
        if (Objects.requireNonNull(jwt.getExpiresAt()).isBefore(Instant.now())) {
            throw new UnauthorizedException("expired date");
        }
    }

}