package com.fraude.casos;

import com.fraude.common.seguridad.SeguridadJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server de casos (§10, §11). Los permisos que dependen del caso (analista asignado)
 * los comprueba {@code MaquinaEstadosCaso}.
 */
@Configuration
public class SeguridadConfig {

    private static final String[] LECTURA = {"ADMIN", "SUPERVISOR", "ANALISTA", "AUDITOR"};
    private static final String[] TRABAJO = {"ADMIN", "SUPERVISOR", "ANALISTA"};

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/metrics/**", "/actuator/prometheus")
                        .permitAll()
                        // Swagger: el gateway no enruta estas rutas; solo se ven en el puerto del servicio.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Handshake del WebSocket: el token se valida en el CONNECT de STOMP (AutenticacionStomp).
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/casos", "/api/v1/casos/**", "/api/v1/metricas/casos")
                        .hasAnyRole(LECTURA)
                        .requestMatchers(HttpMethod.POST, "/api/v1/casos/*/asignar").hasAnyRole("ADMIN", "SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/casos/*/tomar", "/api/v1/casos/*/investigar",
                                "/api/v1/casos/*/cerrar", "/api/v1/casos/*/notas").hasAnyRole(TRABAJO)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(SeguridadJwt.convertidor())))
                .build();
    }
}
