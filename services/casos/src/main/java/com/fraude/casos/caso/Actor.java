package com.fraude.casos.caso;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Usuario que actúa sobre un caso: su nombre ({@code preferred_username}) y sus roles del realm. */
public record Actor(String usuario, Set<String> roles) {

    public Actor {
        roles = Set.copyOf(roles);
    }

    /** SUPERVISOR y ADMIN tienen los mismos permisos sobre casos (§10). */
    public boolean esSupervisor() {
        return roles.contains("SUPERVISOR") || roles.contains("ADMIN");
    }

    public boolean puedeTrabajarCasos() {
        return esSupervisor() || roles.contains("ANALISTA");
    }

    public static Actor de(Authentication autenticacion) {
        Jwt jwt = (Jwt) autenticacion.getPrincipal();
        String nombre = jwt.getClaimAsString("preferred_username");
        Set<String> roles = autenticacion.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
        return new Actor(nombre != null && !nombre.isBlank() ? nombre : jwt.getSubject(), roles);
    }
}
