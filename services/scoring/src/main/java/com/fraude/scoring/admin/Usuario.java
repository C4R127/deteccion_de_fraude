package com.fraude.scoring.admin;

import org.springframework.security.oauth2.jwt.Jwt;

/** Nombre del usuario autenticado para auditoría ({@code creada_por}). */
final class Usuario {

    private Usuario() {
    }

    static String de(Jwt jwt) {
        String nombre = jwt.getClaimAsString("preferred_username");
        return nombre != null && !nombre.isBlank() ? nombre : jwt.getSubject();
    }
}
