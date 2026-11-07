package com.fraude.scoring.admin;

import com.fraude.scoring.bloqueo.ListaBloqueoService;
import com.fraude.scoring.bloqueo.ListaBloqueoService.Entrada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Listas de bloqueo manuales (§11, ADR 0014): DELETE vence la entrada, no la borra. */
@RestController
@RequestMapping("/api/v1/listas-bloqueo")
public class ListaBloqueoController {

    private final ListaBloqueoService bloqueos;

    public ListaBloqueoController(ListaBloqueoService bloqueos) {
        this.bloqueos = bloqueos;
    }

    public record BloqueoRequest(
            @NotNull @Pattern(regexp = "TARJETA|DISPOSITIVO|COMERCIO") String tipo,
            @NotBlank String valor,
            @NotBlank String motivo,
            Instant vigenteHasta) {
    }

    @GetMapping
    public List<Entrada> listar() {
        return bloqueos.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Entrada crear(@Valid @RequestBody BloqueoRequest req, @AuthenticationPrincipal Jwt jwt) {
        return bloqueos.bloquear(req.tipo(), req.valor(), req.motivo(), "MANUAL", req.vigenteHasta(), Usuario.de(jwt));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vencer(@PathVariable UUID id) {
        bloqueos.vencer(id, Instant.now());
    }
}
