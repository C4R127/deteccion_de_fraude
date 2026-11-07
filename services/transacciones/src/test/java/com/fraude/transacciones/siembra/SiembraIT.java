package com.fraude.transacciones.siembra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraude.transacciones.TestcontainersConfig;
import com.fraude.transacciones.TokensDePrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class SiembraIT {

    private static final String CLIENTE = "6f1c2d3e-0000-4000-8000-000000000001";
    private static final String TARJETA = "6f1c2d3e-0000-4000-8000-0000000000a1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.execute("TRUNCATE dispositivo, tarjeta, comercio, cliente CASCADE");
    }

    private static String siembra(String nombre) {
        return """
                {
                  "clientes": [{"id": "%s", "nombre": "%s", "segmento": "ESTANDAR",
                                "ciudadResidencia": "Lima", "fechaAlta": "2025-03-01T10:00:00Z"}],
                  "tarjetas": [{"id": "%s", "clienteId": "%s", "token": "tok_8c1f42a9",
                                "ultimos4": "4821", "estado": "ACTIVA",
                                "fechaEmision": "2025-03-02T10:00:00Z"}],
                  "comercios": [
                    {"id": "COM-00001", "nombre": "Supermercado Lima 1", "mcc": "5411", "pais": "PE",
                     "ciudad": "Lima", "latitud": -12.093100, "longitud": -77.046500, "online": false},
                    {"id": "COM-00002", "nombre": "Tienda en linea 1", "mcc": "5969", "pais": "PE",
                     "ciudad": "Lima", "latitud": null, "longitud": null, "online": true}],
                  "dispositivos": [{"huella": "dev_0a1b2c3d4e5f", "clienteId": "%s"}]
                }
                """.formatted(CLIENTE, nombre, TARJETA, CLIENTE, CLIENTE);
    }

    private int contar(String tabla) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabla, Integer.class);
    }

    @Test
    void siembra_crea_los_datos_maestros() throws Exception {
        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA)).contentType(MediaType.APPLICATION_JSON)
                        .content(siembra("Juan Perez")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientes").value(1))
                .andExpect(jsonPath("$.tarjetas").value(1))
                .andExpect(jsonPath("$.comercios").value(2))
                .andExpect(jsonPath("$.dispositivos").value(1));

        assertThat(contar("cliente")).isEqualTo(1);
        assertThat(contar("tarjeta")).isEqualTo(1);
        assertThat(contar("comercio")).isEqualTo(2);
        assertThat(contar("dispositivo")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT latitud::text FROM comercio WHERE id = 'COM-00001'",
                String.class)).isEqualTo("-12.093100");
    }

    @Test
    void enviar_la_siembra_dos_veces_no_duplica_y_actualiza() throws Exception {
        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA)).contentType(MediaType.APPLICATION_JSON)
                .content(siembra("Juan Perez"))).andExpect(status().isOk());
        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA)).contentType(MediaType.APPLICATION_JSON)
                .content(siembra("Juan Perez Actualizado"))).andExpect(status().isOk());

        assertThat(contar("cliente")).isEqualTo(1);
        assertThat(contar("tarjeta")).isEqualTo(1);
        assertThat(contar("comercio")).isEqualTo(2);
        assertThat(contar("dispositivo")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nombre FROM cliente", String.class))
                .isEqualTo("Juan Perez Actualizado");
    }

    @Test
    void rechaza_un_numero_de_tarjeta_en_lugar_de_token() throws Exception {
        String cuerpo = siembra("Juan Perez").replace("tok_8c1f42a9", "4111111111111111");

        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA)).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isUnprocessableEntity());

        assertThat(contar("tarjeta")).isZero();
    }

    @Test
    void rechaza_ultimos4_que_no_son_cuatro_digitos() throws Exception {
        String cuerpo = siembra("Juan Perez").replace("\"4821\"", "\"48210\"");

        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.SISTEMA)).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sin_token_devuelve_401() throws Exception {
        mvc.perform(post("/internal/v1/siembra").contentType(MediaType.APPLICATION_JSON)
                        .content(siembra("Juan Perez")))
                .andExpect(status().isUnauthorized());

        assertThat(contar("cliente")).isZero();
    }

    @Test
    void rol_distinto_de_sistema_devuelve_403() throws Exception {
        mvc.perform(post("/internal/v1/siembra").headers(TokensDePrueba.cabeceras(TokensDePrueba.ANALISTA))
                        .contentType(MediaType.APPLICATION_JSON).content(siembra("Juan Perez")))
                .andExpect(status().isForbidden());

        assertThat(contar("cliente")).isZero();
    }
}
