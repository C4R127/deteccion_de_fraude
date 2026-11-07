package com.fraude.casos.caso;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.eventos.Resolucion;
import com.fraude.common.json.JsonFraude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a {@code caso}, {@code nota_caso} e {@code historial_caso} (§5.3). */
@Repository
public class CasoStore {

    public record Nota(UUID id, String autorId, String texto, Instant creadaEn) {
    }

    public record Historial(String estadoAnterior, String estadoNuevo, String usuarioId, Instant fecha,
            String comentario) {
    }

    /** Filtros de la bandeja (§8.3); los nulos no filtran. */
    public record Filtros(EstadoCaso estado, Prioridad prioridad, String analista, Instant desde, Instant hasta) {
    }

    public record Pagina(List<Caso> contenido, int pagina, int tamano, long total) {
    }

    private static final TypeReference<List<ReglaDisparada>> REGLAS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JsonFraude.objectMapper();

    public CasoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserta el caso si su transacción no tenía uno; devuelve su id solo si lo creó. */
    public Optional<UUID> insertar(DecisionSospechosaEvento e, Instant ahora) {
        return jdbc.query("""
                INSERT INTO caso (id, transaccion_id, tarjeta_id, cliente_id, dispositivo_huella, monto, score,
                                  decision, reglas_disparadas, prioridad, estado, creado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'NUEVO', ?)
                ON CONFLICT (transaccion_id) DO NOTHING
                RETURNING id
                """, (rs, n) -> rs.getObject(1, UUID.class),
                UUID.randomUUID(), e.transaccionId(), e.tarjetaId(), e.clienteId(), e.dispositivoHuella(), e.monto(),
                e.score(), e.decision().name(), aJson(e.reglasDisparadas()), Prioridad.de(e.decision()).name(),
                Timestamp.from(ahora)).stream().findFirst();
    }

    public Optional<Caso> buscar(UUID id) {
        return jdbc.query("SELECT * FROM caso WHERE id = ?", this::caso, id).stream().findFirst();
    }

    /** Lee el caso bloqueando la fila hasta el fin de la transacción (transiciones concurrentes). */
    public Optional<Caso> bloquear(UUID id) {
        return jdbc.query("SELECT * FROM caso WHERE id = ? FOR UPDATE", this::caso, id).stream().findFirst();
    }

    public void actualizar(UUID id, EstadoActual nuevo, Resolucion resolucion, Instant cerradoEn) {
        jdbc.update("UPDATE caso SET estado = ?, analista_id = ?, resolucion = ?, cerrado_en = ? WHERE id = ?",
                nuevo.estado().name(), nuevo.analistaId(), resolucion == null ? null : resolucion.name(),
                cerradoEn == null ? null : Timestamp.from(cerradoEn), id);
    }

    public void registrarHistorial(UUID casoId, EstadoCaso anterior, EstadoCaso nuevo, String usuario, Instant fecha,
            String comentario) {
        jdbc.update("""
                INSERT INTO historial_caso (id, caso_id, estado_anterior, estado_nuevo, usuario_id, fecha, comentario)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), casoId, anterior == null ? null : anterior.name(), nuevo.name(), usuario,
                Timestamp.from(fecha), comentario);
    }

    public List<Historial> historial(UUID casoId) {
        return jdbc.query("SELECT * FROM historial_caso WHERE caso_id = ? ORDER BY fecha, id",
                (rs, n) -> new Historial(rs.getString("estado_anterior"), rs.getString("estado_nuevo"),
                        rs.getString("usuario_id"), rs.getTimestamp("fecha").toInstant(), rs.getString("comentario")),
                casoId);
    }

    public Nota agregarNota(UUID casoId, String autor, String texto, Instant ahora) {
        Nota nota = new Nota(UUID.randomUUID(), autor, texto, ahora);
        jdbc.update("INSERT INTO nota_caso (id, caso_id, autor_id, texto, creada_en) VALUES (?, ?, ?, ?, ?)",
                nota.id(), casoId, autor, texto, Timestamp.from(ahora));
        return nota;
    }

    public List<Nota> notas(UUID casoId) {
        return jdbc.query("SELECT * FROM nota_caso WHERE caso_id = ? ORDER BY creada_en, id",
                (rs, n) -> new Nota(rs.getObject("id", UUID.class), rs.getString("autor_id"), rs.getString("texto"),
                        rs.getTimestamp("creada_en").toInstant()),
                casoId);
    }

    /** Bandeja (§8.3): prioridad ALTA primero, luego los más antiguos. */
    public Pagina bandeja(Filtros f, int pagina, int tamano) {
        StringBuilder donde = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (f.estado() != null) {
            donde.append(" AND estado = ?");
            args.add(f.estado().name());
        }
        if (f.prioridad() != null) {
            donde.append(" AND prioridad = ?");
            args.add(f.prioridad().name());
        }
        if (f.analista() != null && !f.analista().isBlank()) {
            donde.append(" AND analista_id = ?");
            args.add(f.analista());
        }
        if (f.desde() != null) {
            donde.append(" AND creado_en >= ?");
            args.add(Timestamp.from(f.desde()));
        }
        if (f.hasta() != null) {
            donde.append(" AND creado_en < ?");
            args.add(Timestamp.from(f.hasta()));
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM caso" + donde, Long.class, args.toArray());
        List<Object> conPagina = new ArrayList<>(args);
        conPagina.add(tamano);
        conPagina.add((long) pagina * tamano);
        List<Caso> contenido = jdbc.query("SELECT * FROM caso" + donde
                + " ORDER BY CASE prioridad WHEN 'ALTA' THEN 0 ELSE 1 END, creado_en, id LIMIT ? OFFSET ?",
                this::caso, conPagina.toArray());
        return new Pagina(contenido, pagina, tamano, total);
    }

    private Caso caso(ResultSet rs, int n) throws SQLException {
        Timestamp cerrado = rs.getTimestamp("cerrado_en");
        String resolucion = rs.getString("resolucion");
        return new Caso(rs.getObject("id", UUID.class), rs.getObject("transaccion_id", UUID.class),
                rs.getObject("tarjeta_id", UUID.class), rs.getObject("cliente_id", UUID.class),
                rs.getString("dispositivo_huella"), rs.getBigDecimal("monto"), rs.getInt("score"),
                Decision.valueOf(rs.getString("decision")), deJson(rs.getString("reglas_disparadas")),
                Prioridad.valueOf(rs.getString("prioridad")), EstadoCaso.valueOf(rs.getString("estado")),
                rs.getString("analista_id"), rs.getTimestamp("creado_en").toInstant(),
                cerrado == null ? null : cerrado.toInstant(),
                resolucion == null ? null : Resolucion.valueOf(resolucion));
    }

    private String aJson(List<ReglaDisparada> reglas) {
        try {
            return json.writeValueAsString(reglas);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ReglaDisparada> deJson(String valor) {
        try {
            return json.readValue(valor, REGLAS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
