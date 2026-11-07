package com.fraude.perfiles.perfil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Escribe {@code perfil:{clienteId}} como hash con los campos de §5.4 (listas en CSV). */
public class EscritorPerfilValkey implements EscritorPerfil {

    private final StringRedisTemplate redis;

    public EscritorPerfilValkey(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void escribir(String clienteId, Perfil p) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("n30d", String.valueOf(p.n30d()));
        campos.put("promedio30d", p.promedio30d().toPlainString());
        campos.put("desviacion30d", p.desviacion30d().toPlainString());
        campos.put("paises", String.join(",", p.paises()));
        campos.put("mccs", String.join(",", p.mccs()));
        campos.put("horas", p.horas().stream().map(String::valueOf).collect(Collectors.joining(",")));
        campos.put("actualizado", String.valueOf(p.actualizado().toEpochMilli()));
        redis.opsForHash().putAll("perfil:" + clienteId, campos);
    }
}
