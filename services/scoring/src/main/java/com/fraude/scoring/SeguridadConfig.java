package com.fraude.scoring;

import com.fraude.common.seguridad.SeguridadJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server del scoring (§10, §11, ADR 0008): reglas y listas para ADMIN y SUPERVISOR;
 * consultas para los roles de lectura; {@code /internal/v1/evaluaciones} sin token (red privada).
 */
@Configuration
public class SeguridadConfig {

    private static final String[] LECTURA = {"ADMIN", "SUPERVISOR", "ANALISTA", "AUDITOR"};

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/metrics/**").permitAll()
                        // Swagger: el gateway no enruta estas rutas; solo se ven en el puerto del servicio.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/v1/evaluaciones").permitAll()
                        .requestMatchers("/api/v1/reglas/**", "/api/v1/listas-bloqueo/**").hasAnyRole("ADMIN", "SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/metricas/verdad").hasAnyRole("ADMIN", "SISTEMA")
                        .requestMatchers(HttpMethod.GET, "/api/v1/clientes/*/perfil", "/api/v1/evaluaciones/**",
                                "/api/v1/metricas").hasAnyRole(LECTURA)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(SeguridadJwt.convertidor())))
                .build();
    }
}
