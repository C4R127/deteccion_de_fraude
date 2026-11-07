package com.fraude.common.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesRealmConverterTest {

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("usuario-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void convierte_roles_del_realm_en_authorities_con_prefijo_role() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("SISTEMA", "offline_access"))).build();

        var authorities = new RolesRealmConverter().convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SISTEMA", "ROLE_offline_access");
    }

    @Test
    void sin_realm_access_no_hay_roles() {
        Jwt jwt = token().claim("scope", "profile").build();

        assertThat(new RolesRealmConverter().convert(jwt)).isEmpty();
    }

    @Test
    void el_convertidor_de_autenticacion_usa_los_roles_del_realm() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("ANALISTA"))).build();

        AbstractAuthenticationToken auth = SeguridadJwt.convertidor().convert(jwt);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ANALISTA");
        assertThat(auth.getName()).isEqualTo("usuario-1");
    }
}
