package com.fraude.perfiles;

import com.fraude.common.eventos.TransaccionCreadaEvento;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Tiempo del evento = campo {@code fecha} del payload, no la hora de publicación: el modo
 * historia del generador produce transacciones con fechas pasadas.
 */
public class ExtractorFechaEvento implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> registro, long tiempoParticion) {
        if (registro.value() instanceof TransaccionCreadaEvento evento && evento.fecha() != null) {
            return evento.fecha().toEpochMilli();
        }
        return tiempoParticion;
    }
}
