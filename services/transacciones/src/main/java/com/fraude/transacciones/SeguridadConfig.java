package com.fraude.transacciones;

import com.fraude.common.seguridad.SeguridadJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server de transacciones (§10, ADR 0008): crear transacciones y sembrar exige el
 * rol SISTEMA; el resto de {@code /api/v1} exige un token válido.
 */
@Configuration
public class SeguridadConfig {

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Métricas (outbox.pendientes, outbox.fallos) para monitoreo; el servicio no se expone fuera.
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/metrics/**", "/actuator/prometheus")
                        .permitAll()
                        // Swagger: el gateway no enruta estas rutas; solo se ven en el puerto del servicio.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/siembra").hasRole("SISTEMA")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transacciones").hasRole("SISTEMA")
                        .requestMatchers(HttpMethod.GET, "/api/v1/clientes/**", "/api/v1/metricas/transacciones")
                        .hasAnyRole("ADMIN", "SUPERVISOR", "ANALISTA", "AUDITOR")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(SeguridadJwt.convertidor())))
                .build();
    }
}
