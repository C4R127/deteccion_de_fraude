package com.fraude.scoring.casos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.CasoCerradoEvento;
import com.fraude.common.eventos.Resolucion;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import com.fraude.scoring.bloqueo.ListaBloqueoService;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consume {@code casos.eventos} (§8.2): un caso cerrado como FRAUDE_CONFIRMADO bloquea la
 * tarjeta y el dispositivo con origen CASO. Idempotente por {@code casoId} en la misma
 * transacción que el bloqueo; los mensajes ilegibles o incompletos van a la DLT
 * (reglas de Kafka de CLAUDE.md, configuradas en {@link ConsumidoresConfig}).
 */
@Component
public class CierreCasoConsumidor {

    static final String CONSUMIDOR = "scoring.bloqueo-por-caso";
    private static final Logger log = LoggerFactory.getLogger(CierreCasoConsumidor.class);

    private final ObjectMapper json = JsonFraude.objectMapper();
    private final ListaBloqueoService bloqueos;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CierreCasoConsumidor(ListaBloqueoService bloqueos, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.bloqueos = bloqueos;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @KafkaListener(topics = Topicos.CASOS_EVENTOS, groupId = "scoring")
    public void consumir(ConsumerRecord<String, String> registro) {
        CasoCerradoEvento evento = leer(registro.value());
        tx.executeWithoutResult(estado -> {
            int nuevo = jdbc.update("INSERT INTO mensaje_procesado (consumidor, evento_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", CONSUMIDOR, evento.casoId().toString());
            if (nuevo == 0) {
                log.info("Caso {} ya procesado; se ignora el evento repetido", evento.casoId());
                return;
            }
            if (evento.resolucion() == Resolucion.FRAUDE_CONFIRMADO) {
                String motivo = "caso " + evento.casoId() + " cerrado como fraude confirmado";
                String usuario = "caso:" + evento.casoId();
                bloqueos.bloquear("TARJETA", evento.tarjetaId().toString(), motivo, "CASO", null, usuario);
                if (evento.dispositivoHuella() != null && !evento.dispositivoHuella().isBlank()) {
                    bloqueos.bloquear("DISPOSITIVO", evento.dispositivoHuella(), motivo, "CASO", null, usuario);
                }
            }
        });
    }

    /** Lee y valida el evento; si no se puede, la excepción lo envía a la DLT sin reintentos. */
    static CasoCerradoEvento leer(String valor) {
        CasoCerradoEvento evento;
        try {
            evento = JsonFraude.objectMapper().readValue(valor, CasoCerradoEvento.class);
        } catch (JsonProcessingException e) {
            throw new EventoInvalidoException("JSON ilegible: " + e.getOriginalMessage());
        }
        List<String> faltan = new ArrayList<>();
        if (evento.casoId() == null) {
            faltan.add("casoId");
        }
        if (evento.tarjetaId() == null) {
            faltan.add("tarjetaId");
        }
        if (evento.resolucion() == null) {
            faltan.add("resolucion");
        }
        if (!faltan.isEmpty()) {
            throw new EventoInvalidoException("faltan campos obligatorios: " + String.join(", ", faltan));
        }
        return evento;
    }
}
