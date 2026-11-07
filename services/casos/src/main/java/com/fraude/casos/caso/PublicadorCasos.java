package com.fraude.casos.caso;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.CasoCerradoEvento;
import com.fraude.common.eventos.Resolucion;
import com.fraude.common.eventos.Topicos;
import com.fraude.common.json.JsonFraude;
import com.fraude.common.outbox.OutboxStore;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Publica {@link CasoCerradoEvento} (§8.2) por el outbox: se guarda en la misma transacción
 * que el cierre y el publicador lo envía a {@code casos.eventos} con clave {@code tarjetaId}.
 */
@Component
public class PublicadorCasos {

    private final OutboxStore outbox;
    private final ObjectMapper json = JsonFraude.objectMapper();

    public PublicadorCasos(OutboxStore outbox) {
        this.outbox = outbox;
    }

    /** Debe llamarse dentro de la transacción del cierre. */
    public void casoCerrado(Caso caso, Resolucion resolucion, Instant fecha) {
        var evento = new CasoCerradoEvento(CasoCerradoEvento.VERSION_ACTUAL, caso.id(), caso.transaccionId(),
                caso.tarjetaId(), caso.dispositivoHuella(), resolucion, fecha);
        try {
            outbox.guardar(Topicos.CASOS_EVENTOS, caso.tarjetaId().toString(), json.writeValueAsString(evento));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el cierre del caso " + caso.id(), e);
        }
    }
}
