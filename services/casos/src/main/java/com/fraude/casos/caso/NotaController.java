package com.fraude.casos.caso;

import com.fraude.casos.caso.CasoStore.Nota;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notas de un caso (§5.3, §11). */
@RestController
public class NotaController {

    private final CasoService casos;

    public NotaController(CasoService casos) {
        this.casos = casos;
    }

    public record NotaRequest(@NotBlank @Size(max = 4000) String texto) {
    }

    @PostMapping("/api/v1/casos/{id}/notas")
    @ResponseStatus(HttpStatus.CREATED)
    public Nota agregar(@PathVariable UUID id, @Valid @RequestBody NotaRequest req, Authentication auth) {
        return casos.agregarNota(id, Actor.de(auth), req.texto());
    }
}
