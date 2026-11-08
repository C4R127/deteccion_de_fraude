package com.fraude.scoring.metricas;

import com.fraude.common.errores.SolicitudInvalidaException;
import com.fraude.scoring.metricas.MetricasService.Metricas;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Métricas de detección (§9) y carga de la verdad del generador (ADR 0017). */
@RestController
@RequestMapping("/api/v1/metricas")
public class MetricasController {

    static final Duration VENTANA_POR_DEFECTO = Duration.ofHours(24);
    static final int MAXIMO_POR_LOTE = 10_000;

    private final MetricasService metricas;
    private final JdbcTemplate jdbc;

    public MetricasController(MetricasService metricas, JdbcTemplate jdbc) {
        this.metricas = metricas;
        this.jdbc = jdbc;
    }

    public record Etiqueta(UUID idExterno, String escenario) {
    }

    public record CargaVerdad(int recibidas, int nuevas) {
    }

    /** Ventana {@code [desde, hasta)}; por defecto, las últimas 24 horas. */
    @GetMapping
    public Metricas obtener(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta) {
        Instant fin = hasta != null ? hasta : Instant.now();
        Instant inicio = desde != null ? desde : fin.minus(VENTANA_POR_DEFECTO);
        if (!inicio.isBefore(fin)) {
            throw new SolicitudInvalidaException("desde debe ser anterior a hasta");
        }
        return metricas.calcular(inicio, fin);
    }

    /** Guarda o actualiza las etiquetas de {@code verdad.jsonl}: subir dos veces no duplica. */
    @PostMapping("/verdad")
    @Transactional
    public CargaVerdad cargarVerdad(@RequestBody List<Etiqueta> etiquetas) {
        if (etiquetas == null || etiquetas.isEmpty() || etiquetas.size() > MAXIMO_POR_LOTE) {
            throw new SolicitudInvalidaException("Envía entre 1 y " + MAXIMO_POR_LOTE + " etiquetas por lote");
        }
        int nuevas = 0;
        for (Etiqueta e : etiquetas) {
            if (e == null || e.idExterno() == null || e.escenario() == null || e.escenario().isBlank()) {
                throw new SolicitudInvalidaException("Cada etiqueta necesita idExterno y escenario");
            }
            // xmax = 0 solo en la fila recién insertada; una actualización devuelve false.
            Boolean nueva = jdbc.queryForObject("""
                    INSERT INTO etiqueta_verdad (transaccion_id_externo, escenario) VALUES (?, ?)
                    ON CONFLICT (transaccion_id_externo) DO UPDATE SET escenario = EXCLUDED.escenario
                    RETURNING (xmax = 0)
                    """, Boolean.class, e.idExterno(), e.escenario().strip());
            nuevas += Boolean.TRUE.equals(nueva) ? 1 : 0;
        }
        return new CargaVerdad(etiquetas.size(), nuevas);
    }
}
