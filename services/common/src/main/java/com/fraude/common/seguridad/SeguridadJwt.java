package com.fraude.common.seguridad;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Configuración común de Resource Server (ADR 0008). La validación de emisor y audiencia
 * ({@code servicios}) se configura con las propiedades de Spring Boot
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} y {@code .audiences}.
 */
public final class SeguridadJwt {

    /** Claim con el cliente OAuth que pidió el token (clave del límite de uso). */
    public static final String CLAIM_CLIENTE = "azp";

    private SeguridadJwt() {
    }

    /** Convertidor de JWT a autenticación con los roles del realm como {@code ROLE_*}. */
    public static JwtAuthenticationConverter convertidor() {
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(new RolesRealmConverter());
        return convertidor;
    }
}
