package com.fraude.common.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.common.json.JsonFraude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventosSerializacionTest {

    private static final Instant FECHA = Instant.parse("2026-11-06T14:30:12Z");
    private static final BigDecimal MONTO = new BigDecimal("189.90");

    private final ObjectMapper mapper = JsonFraude.objectMapper();

    @Test
    void transaccion_creada_ida_y_vuelta_sin_perdida() throws Exception {
        var evento = new TransaccionCreadaEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "COM-00421", "5411", null, MONTO, "PEN",
                Canal.POS, "PE", new BigDecimal("-12.093100"), new BigDecimal("-77.046500"), FECHA,
                Decision.REVISAR, 55, false);

        String json = mapper.writeValueAsString(evento);
        var leido = mapper.readValue(json, TransaccionCreadaEvento.class);

        assertThat(leido).isEqualTo(evento);
    }

    @Test
    void decision_sospechosa_ida_y_vuelta_sin_perdida() throws Exception {
        var evento = new DecisionSospechosaEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "huella-abc", MONTO, 75, Decision.RECHAZAR,
                List.of(new ReglaDisparada("R01", 40, "monto 2450.00 PEN, promedio 180.00 PEN"),
                        new ReglaDisparada("R03", 50, "Lima a Cusco en 10 min")),
                FECHA);

        String json = mapper.writeValueAsString(evento);
        var leido = mapper.readValue(json, DecisionSospechosaEvento.class);

        assertThat(leido).isEqualTo(evento);
    }

    @Test
    void caso_cerrado_ida_y_vuelta_sin_perdida() throws Exception {
        var evento = new CasoCerradoEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, Resolucion.FRAUDE_CONFIRMADO, FECHA);

        String json = mapper.writeValueAsString(evento);
        var leido = mapper.readValue(json, CasoCerradoEvento.class);

        assertThat(leido).isEqualTo(evento);
    }

    @Test
    void monto_se_serializa_como_numero_y_conserva_escala_2() throws Exception {
        var evento = new DecisionSospechosaEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, MONTO, 40, Decision.REVISAR, List.of(), FECHA);

        String json = mapper.writeValueAsString(evento);
        JsonNode nodo = mapper.readTree(json);
        var leido = mapper.readValue(json, DecisionSospechosaEvento.class);

        assertThat(json).contains("\"monto\":189.90");
        assertThat(nodo.get("monto").isNumber()).isTrue();
        assertThat(leido.monto().scale()).isEqualTo(2);
        assertThat(leido.monto()).isEqualTo(MONTO);
    }

    @Test
    void fecha_se_serializa_en_iso_8601_utc() throws Exception {
        var evento = new CasoCerradoEvento(1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, Resolucion.FALSO_POSITIVO, FECHA);

        String json = mapper.writeValueAsString(evento);

        assertThat(json).contains("\"fecha\":\"2026-11-06T14:30:12Z\"");
    }

    @Test
    void ignora_campos_desconocidos_de_versiones_futuras() throws Exception {
        String json = """
                {"version":2,"casoId":"%s","transaccionId":"%s","tarjetaId":"%s",
                 "dispositivoHuella":null,"resolucion":"FALSO_POSITIVO",
                 "fecha":"2026-11-06T14:30:12Z","campoNuevo":"x"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var leido = mapper.readValue(json, CasoCerradoEvento.class);

        assertThat(leido.version()).isEqualTo(2);
        assertThat(leido.resolucion()).isEqualTo(Resolucion.FALSO_POSITIVO);
    }

    @Test
    void topicos_coinciden_con_la_especificacion() {
        assertThat(Topicos.TRANSACCIONES_CREADAS).isEqualTo("transacciones.creadas");
        assertThat(Topicos.DECISIONES_SOSPECHOSAS).isEqualTo("decisiones.sospechosas");
        assertThat(Topicos.CASOS_EVENTOS).isEqualTo("casos.eventos");
    }
}
