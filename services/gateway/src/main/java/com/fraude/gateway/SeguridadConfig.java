package com.fraude.gateway;

import com.fraude.common.seguridad.SeguridadJwt;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Entrada única (§10, ADR 0008): valida el JWT de {@code /api/v1}, exige SISTEMA para crear
 * transacciones y no expone {@code /internal/v1} (no hay ruta: responde 404).
 */
@Configuration
@EnableWebFluxSecurity
public class SeguridadConfig {

    @Bean
    SecurityWebFilterChain seguridad(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        // Sin ruta en el gateway: se deja pasar para que responda 404.
                        .pathMatchers("/internal/**").permitAll()
                        // El token del WebSocket se valida en el CONNECT de STOMP (Tarea 5.2).
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/transacciones").hasRole("SISTEMA")
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        new ReactiveJwtAuthenticationConverterAdapter(SeguridadJwt.convertidor()))))
                .build();
    }

    /** Clave del límite de uso: el cliente OAuth del token (claim {@code azp}). */
    @Bean
    KeyResolver clienteOAuth() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> ((JwtAuthenticationToken) p).getToken().getClaimAsString(SeguridadJwt.CLAIM_CLIENTE))
                .filter(cliente -> cliente != null && !cliente.isBlank());
    }
}
