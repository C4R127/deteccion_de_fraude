package com.fraude.transacciones.maestros;

import com.fraude.common.errores.RecursoNoEncontradoException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Datos del cliente para el detalle del caso; el nombre va enmascarado para AUDITOR (§10, ADR 0016). */
@RestController
public class ClienteController {

    /** Roles que ven el nombre completo; cualquier otro rol de lectura (AUDITOR) lo ve enmascarado. */
    private static final Set<String> VEN_NOMBRE = Set.of("ROLE_ADMIN", "ROLE_SUPERVISOR", "ROLE_ANALISTA");

    private final ClienteRepository clientes;

    public ClienteController(ClienteRepository clientes) {
        this.clientes = clientes;
    }

    public record ClienteResponse(UUID id, String nombre, Segmento segmento, String ciudadResidencia,
            Instant fechaAlta) {
    }

    @GetMapping("/api/v1/clientes/{id}")
    public ClienteResponse obtener(@PathVariable UUID id, Authentication auth) {
        Cliente c = clientes.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente " + id + " no existe"));
        boolean completo = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(VEN_NOMBRE::contains);
        String nombre = completo ? c.getNombre() : EnmascaradorNombre.enmascarar(c.getNombre());
        return new ClienteResponse(c.getId(), nombre, c.getSegmento(), c.getCiudadResidencia(), c.getFechaAlta());
    }
}
