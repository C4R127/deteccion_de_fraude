package com.fraude.common.outbox;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Envía el JSON ya serializado del outbox (sin cabeceras de tipo Java) y espera el ack.
 * El productor usa {@code acks=all} y {@code enable.idempotence=true} (application.yml).
 */
@Component
public class EnviadorKafka implements EnviadorEventos {

    private final KafkaTemplate<String, String> kafka;
    private final Duration espera;

    public EnviadorKafka(KafkaTemplate<String, String> kafka,
            @Value("${outbox.publicador.espera-ack:30s}") Duration espera) {
        this.kafka = kafka;
        this.espera = espera;
    }

    @Override
    public void enviar(String topico, String clave, String payload) throws Exception {
        kafka.send(topico, clave, payload).get(espera.toMillis(), TimeUnit.MILLISECONDS);
    }
}
