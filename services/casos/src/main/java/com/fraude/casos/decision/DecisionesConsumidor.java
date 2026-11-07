package com.fraude.casos.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fraude.casos.caso.CasoService;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.DecisionSospechosaEvento;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume {@code decisiones.sospechosas} y abre un caso NUEVO por transacción (§8.1).
 * Idempotente por {@code transaccionId} (restricción unique, en la misma sentencia que crea el
 * caso); los mensajes ilegibles o incompletos van a la DLT (reglas de Kafka de CLAUDE.md).
 */
@Component
public class DecisionesConsumidor {

    private static final Logger log = LoggerFactory.getLogger(DecisionesConsumidor.class);

    private final CasoService casos;

    public DecisionesConsumidor(CasoService casos) {
        this.casos = casos;
    }

    @KafkaListener(topics = Topicos.DECISIONES_SOSPECHOSAS, groupId = "casos")
    public void consumir(ConsumerRecord<String, String> registro) {
        DecisionSospechosaEvento evento = leer(registro.value());
        if (casos.crear(evento).isEmpty()) {
            log.info("La transacción {} ya tiene caso; se ignora el evento repetido", evento.transaccionId());
        }
    }

    /** Lee y valida el evento; si no se puede, la excepción lo envía a la DLT sin reintentos. */
    static DecisionSospechosaEvento leer(String valor) {
        DecisionSospechosaEvento evento;
        try {
            evento = JsonFraude.objectMapper().readValue(valor, DecisionSospechosaEvento.class);
        } catch (JsonProcessingException e) {
            throw new EventoInvalidoException("JSON ilegible: " + e.getOriginalMessage());
        }
        List<String> errores = new ArrayList<>();
        if (evento.transaccionId() == null) {
            errores.add("falta transaccionId");
        }
        if (evento.tarjetaId() == null) {
            errores.add("falta tarjetaId");
        }
        if (evento.clienteId() == null) {
            errores.add("falta clienteId");
        }
        if (evento.monto() == null) {
            errores.add("falta monto");
        }
        if (evento.decision() == null) {
            errores.add("falta decision");
        } else if (evento.decision() == Decision.APROBAR) {
            errores.add("una decisión APROBAR no es sospechosa");
        }
        if (evento.score() < 0 || evento.score() > 100) {
            errores.add("score fuera de 0..100");
        }
        if (!errores.isEmpty()) {
            throw new EventoInvalidoException(String.join(", ", errores));
        }
        return evento;
    }
}
