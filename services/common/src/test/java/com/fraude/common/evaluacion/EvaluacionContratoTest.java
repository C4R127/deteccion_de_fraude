package com.fraude.common.evaluacion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import com.fraude.common.json.JsonFraude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluacionContratoTest {

    private final ObjectMapper mapper = JsonFraude.objectMapper();

    @Test
    void solicitud_ida_y_vuelta_sin_perdida() throws Exception {
        var solicitud = new EvaluacionSolicitud(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "COM-00421", "5411", null, new BigDecimal("189.90"), "PEN", Canal.POS,
                "PE", new BigDecimal("-12.093100"), new BigDecimal("-77.046500"),
                Instant.parse("2026-11-06T14:30:12Z"));

        var leida = mapper.readValue(mapper.writeValueAsString(solicitud), EvaluacionSolicitud.class);

        assertThat(leida).isEqualTo(solicitud);
    }

    @Test
    void resultado_se_lee_del_json_del_scoring() throws Exception {
        String json = """
                {"score": 55, "decision": "REVISAR",
                 "reglasDisparadas": [{"codigo": "R01", "puntos": 40, "detalle": "monto alto"}]}
                """;

        var resultado = mapper.readValue(json, EvaluacionResultado.class);

        assertThat(resultado).isEqualTo(new EvaluacionResultado(55, Decision.REVISAR,
                List.of(new ReglaDisparada("R01", 40, "monto alto"))));
    }
}
