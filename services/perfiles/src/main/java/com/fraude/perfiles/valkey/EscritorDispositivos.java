package com.fraude.perfiles.valkey;

import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code dispositivos:{clienteId}}: huellas conocidas. Solo se agregan las de transacciones
 * APROBAR, para que el dispositivo de un intento sospechoso no pase a ser conocido (§5.4).
 */
public class EscritorDispositivos implements EscritorEvento {

    private final StringRedisTemplate redis;

    public EscritorDispositivos(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void escribir(TransaccionCreadaEvento e) {
        if (e.decision() == Decision.APROBAR && e.dispositivoHuella() != null && !e.dispositivoHuella().isBlank()) {
            redis.opsForSet().add("dispositivos:" + e.clienteId(), e.dispositivoHuella());
        }
    }
}
