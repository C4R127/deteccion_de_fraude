package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.Topicos;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica {@link TransaccionCreadaEvento} en {@link Topicos#TRANSACCIONES_CREADAS} con clave
 * {@code tarjetaId}, solo después del commit: si la base de datos hace rollback no se publica.
 */
@Component
public class PublicadorTransacciones {

    private static final Logger log = LoggerFactory.getLogger(PublicadorTransacciones.class);

    private final KafkaTemplate<String, TransaccionCreadaEvento> kafka;

    public PublicadorTransacciones(KafkaTemplate<String, TransaccionCreadaEvento> kafka) {
        this.kafka = kafka;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publicar(TransaccionCreadaEvento evento) {
        kafka.send(Topicos.TRANSACCIONES_CREADAS, evento.tarjetaId().toString(), evento)
                .whenComplete((resultado, error) -> {
                    if (error != null) {
                        log.error("No se pudo publicar la transacción {} en {}", evento.transaccionId(),
                                Topicos.TRANSACCIONES_CREADAS, error);
                    }
                });
    }
}
