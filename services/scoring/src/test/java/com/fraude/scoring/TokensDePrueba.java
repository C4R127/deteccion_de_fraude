package com.fraude.scoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Sustituye la validación contra Keycloak: el token {@code "rol-<ROL>-<usuario>"} se
 * decodifica como un JWT con ese rol del realm y {@code preferred_username = usuario}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TokensDePrueba {

    public static final String ADMIN = "rol-ADMIN-admin";
    public static final String SUPERVISOR = "rol-SUPERVISOR-supervisor";
    public static final String ANALISTA = "rol-ANALISTA-analista1";
    public static final String AUDITOR = "rol-AUDITOR-auditor";

    @Bean
    JwtDecoder jwtDecoderDePrueba() {
        return token -> {
            String[] partes = token.split("-");
            if (partes.length != 3 || !partes[0].equals("rol")) {
                throw new BadJwtException("token de prueba inválido");
            }
            return Jwt.withTokenValue(token).header("alg", "none").subject("sub-" + partes[2])
                    .claim("preferred_username", partes[2]).claim("azp", "frontend")
                    .audience(List.of("servicios"))
                    .claim("realm_access", Map.of("roles", List.of(partes[1])))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        };
    }

    public static HttpHeaders cabeceras(String token) {
        HttpHeaders cabeceras = new HttpHeaders();
        if (token != null) {
            cabeceras.setBearerAuth(token);
        }
        return cabeceras;
    }
}
