package com.fraude.perfiles.valkey;

import com.fraude.common.perfil.Velocidad;
import com.fraude.common.eventos.TransaccionCreadaEvento;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@code velocidad:{tarjetaId}}: sorted set con miembro = transaccionId y score = fecha del
 * evento en epoch ms. Incluye todas las decisiones (§5.4). Tras agregar, poda lo que tenga más
 * de 1 hora respecto del evento más reciente del set. Reprocesar un evento no lo duplica.
 */
public class EscritorVelocidad implements EscritorEvento {

    static final long UNA_HORA_MS = Velocidad.RETENCION.toMillis();
    /** Sin eventos nuevos, la clave expira sola. */
    static final long EXPIRA_S = 2 * 3600;

    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            local maximo = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')[2]
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (tonumber(maximo) - tonumber(ARGV[3])))
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public EscritorVelocidad(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void escribir(TransaccionCreadaEvento e) {
        redis.execute(SCRIPT, List.of("velocidad:" + e.tarjetaId()),
                String.valueOf(e.fecha().toEpochMilli()), e.transaccionId().toString(),
                String.valueOf(UNA_HORA_MS), String.valueOf(EXPIRA_S));
    }
}
