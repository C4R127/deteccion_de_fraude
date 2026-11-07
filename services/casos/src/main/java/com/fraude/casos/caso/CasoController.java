package com.fraude.casos.caso;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fraude.casos.caso.CasoStore.Filtros;
import com.fraude.casos.caso.CasoStore.Historial;
import com.fraude.casos.caso.CasoStore.Nota;
import com.fraude.casos.caso.CasoStore.Pagina;
import com.fraude.common.eventos.Resolucion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bandeja, detalle y transiciones de casos (§8, §11). */
@RestController
@RequestMapping("/api/v1/casos")
@Validated
public class CasoController {

    private final CasoService casos;
    private final CasoStore store;

    public CasoController(CasoService casos, CasoStore store) {
        this.casos = casos;
        this.store = store;
    }

    /** Detalle: el caso con sus notas e historial (§8.3). */
    public record CasoDetalle(@JsonUnwrapped Caso caso, List<Nota> notas, List<Historial> historial) {
    }

    public record AsignarRequest(String analistaId) {
    }

    /** {@code resolucion} se valida en la máquina de estados para responder 422 con su mensaje. */
    public record CerrarRequest(Resolucion resolucion, String comentario) {
    }

    @GetMapping
    public Pagina bandeja(@RequestParam(required = false) EstadoCaso estado,
            @RequestParam(required = false) Prioridad prioridad,
            @RequestParam(required = false) String analista,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int tamano) {
        return store.bandeja(new Filtros(estado, prioridad, analista, desde, hasta), pagina, tamano);
    }

    @GetMapping("/{id}")
    public CasoDetalle detalle(@PathVariable UUID id) {
        return new CasoDetalle(casos.buscar(id), store.notas(id), store.historial(id));
    }

    @PostMapping("/{id}/tomar")
    public Caso tomar(@PathVariable UUID id, Authentication auth) {
        return casos.tomar(id, Actor.de(auth));
    }

    @PostMapping("/{id}/asignar")
    public Caso asignar(@PathVariable UUID id, @RequestBody AsignarRequest req, Authentication auth) {
        return casos.asignar(id, Actor.de(auth), req.analistaId());
    }

    @PostMapping("/{id}/investigar")
    public Caso investigar(@PathVariable UUID id, Authentication auth) {
        return casos.investigar(id, Actor.de(auth));
    }

    @PostMapping("/{id}/cerrar")
    public Caso cerrar(@PathVariable UUID id, @RequestBody CerrarRequest req, Authentication auth) {
        return casos.cerrar(id, Actor.de(auth), req.resolucion(), req.comentario());
    }
}
