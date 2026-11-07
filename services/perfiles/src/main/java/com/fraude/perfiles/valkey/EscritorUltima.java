package com.fraude.perfiles.valkey;

import com.fraude.common.eventos.TransaccionCreadaEvento;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@code ultima:{tarjetaId}}: hash con {@code lat}, {@code lon}, {@code fecha} (epoch ms) y
 * {@code canal} de la transacción más reciente por tiempo de evento, de cualquier decisión.
 * Un evento atrasado no la reemplaza. Sin coordenadas (ECOMMERCE) se quitan lat y lon.
 */
public class EscritorUltima implements EscritorEvento {

    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
            local actual = redis.call('HGET', KEYS[1], 'fecha')
            if actual and tonumber(actual) > tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HSET', KEYS[1], 'fecha', ARGV[1], 'canal', ARGV[2])
            if ARGV[3] == '' then
              redis.call('HDEL', KEYS[1], 'lat', 'lon')
            else
              redis.call('HSET', KEYS[1], 'lat', ARGV[3], 'lon', ARGV[4])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public EscritorUltima(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void escribir(TransaccionCreadaEvento e) {
        boolean conUbicacion = e.latitud() != null && e.longitud() != null;
        redis.execute(SCRIPT, List.of("ultima:" + e.tarjetaId()),
                String.valueOf(e.fecha().toEpochMilli()), e.canal().name(),
                conUbicacion ? texto(e.latitud()) : "", conUbicacion ? texto(e.longitud()) : "");
    }

    private static String texto(BigDecimal valor) {
        return valor.toPlainString();
    }
}
