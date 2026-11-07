package com.fraude.scoring.motor.reglas;

import com.fraude.common.evaluacion.EvaluacionSolicitud;
import com.fraude.common.eventos.Canal;
import com.fraude.scoring.motor.ContextoEvaluacion;
import com.fraude.scoring.perfil.PerfilContexto;
import com.fraude.scoring.perfil.UltimaUbicacion;
import com.fraude.scoring.regla.Parametros;
import com.fraude.scoring.regla.ReglaVigente;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Constructor de contextos de prueba para las reglas. */
final class Ctx {

    static final Instant FECHA = Instant.parse("2026-11-06T14:30:00Z");
    static final BigDecimal LIMA_LAT = new BigDecimal("-12.046400");
    static final BigDecimal LIMA_LON = new BigDecimal("-77.042800");

    String monto = "100.00";
    String mcc = "5411";
    String pais = "PE";
    Canal canal = Canal.POS;
    BigDecimal lat = LIMA_LAT;
    BigDecimal lon = LIMA_LON;
    Instant fecha = FECHA;
    String huella = null;

    Integer n30d = 20;
    String promedio = "180.00";
    Set<String> paises = Set.of("PE");
    List<Integer> horas = new ArrayList<>(Collections.nCopies(24, 1));
    long previas = 0;
    UltimaUbicacion ultima = null;
    Set<String> dispositivos = Set.of();

    static Ctx tx() {
        return new Ctx();
    }

    Ctx sinPerfil() {
        n30d = null;
        promedio = null;
        paises = Set.of();
        horas = List.of();
        return this;
    }

    ContextoEvaluacion construir() {
        var solicitud = new EvaluacionSolicitud(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "COM-00001", mcc, huella, new BigDecimal(monto), "PEN", canal, pais, lat, lon,
                fecha);
        var perfil = new PerfilContexto(n30d, promedio == null ? null : new BigDecimal(promedio),
                promedio == null ? null : new BigDecimal("40.00"), paises, Set.of("5411"), horas, previas, ultima,
                dispositivos);
        return new ContextoEvaluacion(solicitud, perfil);
    }

    static ReglaVigente regla(String codigo, int puntos, Map<String, Object> parametros) {
        return new ReglaVigente(codigo, codigo, codigo, new Parametros(parametros), puntos, true, 1);
    }
}
