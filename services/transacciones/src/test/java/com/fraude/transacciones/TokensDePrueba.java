package com.fraude.transacciones;

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
 * Sustituye la validación contra Keycloak en las pruebas: el token {@code "rol-SISTEMA"}
 * se decodifica como un JWT con ese rol del realm. Cualquier otro valor es inválido.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TokensDePrueba {

    public static final String SISTEMA = "rol-SISTEMA";
    public static final String ANALISTA = "rol-ANALISTA";

    @Bean
    JwtDecoder jwtDecoderDePrueba() {
        return token -> {
            if (!token.startsWith("rol-")) {
                throw new BadJwtException("token de prueba inválido");
            }
            return Jwt.withTokenValue(token).header("alg", "none").subject("prueba")
                    .claim("azp", "prueba").audience(List.of("servicios"))
                    .claim("realm_access", Map.of("roles", List.of(token.substring(4))))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        };
    }

    public static HttpHeaders cabeceras(String token) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(token);
        return cabeceras;
    }
}
