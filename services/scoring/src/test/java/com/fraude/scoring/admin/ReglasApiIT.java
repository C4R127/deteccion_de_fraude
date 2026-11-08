package com.fraude.scoring.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraude.scoring.TestcontainersConfig;
import com.fraude.scoring.TokensDePrueba;
import com.fraude.scoring.bloqueo.ListaBloqueoService;
import com.fraude.scoring.regla.CatalogoReglas;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** APIs de administración y consulta del scoring (§11, ADR 0014) con sus roles (§10). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class ReglasApiIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CatalogoReglas catalogo;

    @Autowired
    ListaBloqueoService bloqueos;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void restaurar() {
        jdbc.update("DELETE FROM regla WHERE version > 1");
        jdbc.update("UPDATE regla SET vigente = true");
        jdbc.execute("TRUNCATE lista_bloqueo");
        catalogo.recargar();
    }

    private ResponseEntity<String> llamar(HttpMethod metodo, String ruta, String token, String cuerpo) {
        var cabeceras = TokensDePrueba.cabeceras(token);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(ruta, metodo, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(r.getBody());
    }

    // --- Reglas ---

    @Test
    void lista_las_reglas_vigentes() throws Exception {
        var r = llamar(HttpMethod.GET, "/api/v1/reglas", TokensDePrueba.SUPERVISOR, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(r)).extracting(n -> n.get("codigo").asText())
                .containsExactly("R01", "R02", "R03", "R04", "R05", "R06", "R07");
        assertThat(json(r).get(0).get("parametros").get("factor").asInt()).isEqualTo(5);
    }

    @Test
    void editar_una_regla_por_api_crea_version_nueva() throws Exception {
        var r = llamar(HttpMethod.PUT, "/api/v1/reglas/R01", TokensDePrueba.SUPERVISOR,
                "{\"puntos\": 45, \"parametros\": {\"factor\": 4, \"minimoTransacciones\": 10}}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(r).get("version").asInt()).isEqualTo(2);
        assertThat(json(r).get("puntos").asInt()).isEqualTo(45);
        assertThat(catalogo.vigente("R01").version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT creada_por FROM regla WHERE codigo = 'R01' AND version = 2",
                String.class)).isEqualTo("supervisor");
        var versiones = llamar(HttpMethod.GET, "/api/v1/reglas/R01/versiones", TokensDePrueba.ADMIN, null);
        assertThat(json(versiones)).extracting(n -> n.get("version").asInt()).containsExactly(2, 1);
        assertThat(json(versiones).get(1).get("vigente").asBoolean()).isFalse();
    }

    @Test
    void auditor_no_puede_editar_ni_analista_ver_reglas() {
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R01", TokensDePrueba.AUDITOR, "{\"puntos\": 1}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(llamar(HttpMethod.GET, "/api/v1/reglas", TokensDePrueba.ANALISTA, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(llamar(HttpMethod.GET, "/api/v1/reglas", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(catalogo.vigente("R01").version()).isEqualTo(1);
    }

    @Test
    void edicion_invalida_devuelve_422_y_no_crea_version() {
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R01", TokensDePrueba.ADMIN, "{\"puntos\": -5}")
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R01", TokensDePrueba.ADMIN,
                "{\"parametros\": {\"factor\": 4}}").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(catalogo.vigente("R01").version()).isEqualTo(1);
    }

    @Test
    void la_ventana_de_r02_no_supera_lo_que_perfiles_guarda_de_velocidad() throws Exception {
        var mayor = llamar(HttpMethod.PUT, "/api/v1/reglas/R02", TokensDePrueba.ADMIN,
                "{\"parametros\": {\"minimoPrevias\": 5, \"ventanaMinutos\": 61}}");

        assertThat(mayor.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(mayor).get("detail").asText()).contains("60");
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R02", TokensDePrueba.ADMIN,
                "{\"parametros\": {\"minimoPrevias\": 5, \"ventanaMinutos\": 0}}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(catalogo.vigente("R02").version()).isEqualTo(1);
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R02", TokensDePrueba.ADMIN,
                "{\"parametros\": {\"minimoPrevias\": 5, \"ventanaMinutos\": 60}}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void regla_inexistente_devuelve_404() {
        assertThat(llamar(HttpMethod.PUT, "/api/v1/reglas/R99", TokensDePrueba.ADMIN, "{\"puntos\": 1}")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Listas de bloqueo ---

    @Test
    void crear_listar_y_vencer_un_bloqueo_manual() throws Exception {
        UUID tarjeta = UUID.randomUUID();

        var creado = llamar(HttpMethod.POST, "/api/v1/listas-bloqueo", TokensDePrueba.ADMIN,
                "{\"tipo\": \"TARJETA\", \"valor\": \"%s\", \"motivo\": \"reporte del cliente\"}".formatted(tarjeta));

        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = json(creado).get("id").asText();
        assertThat(json(creado).get("origen").asText()).isEqualTo("MANUAL");
        assertThat(json(creado).get("creadaPor").asText()).isEqualTo("admin");
        assertThat(bloqueos.buscar(tarjeta, null, "COM-1", Instant.now())).isPresent();
        var lista = llamar(HttpMethod.GET, "/api/v1/listas-bloqueo", TokensDePrueba.SUPERVISOR, null);
        assertThat(json(lista)).extracting(n -> n.get("valor").asText()).contains(tarjeta.toString());

        var vencido = llamar(HttpMethod.DELETE, "/api/v1/listas-bloqueo/" + id, TokensDePrueba.SUPERVISOR, null);

        assertThat(vencido.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bloqueos.buscar(tarjeta, null, "COM-1", Instant.now())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lista_bloqueo WHERE id = ?::uuid", Integer.class, id))
                .as("la fila se conserva").isEqualTo(1);
    }

    @Test
    void volver_a_bloquear_reactiva_la_fila() throws Exception {
        String cuerpo = "{\"tipo\": \"DISPOSITIVO\", \"valor\": \"dev_x\", \"motivo\": \"m\"}";
        String id = json(llamar(HttpMethod.POST, "/api/v1/listas-bloqueo", TokensDePrueba.ADMIN, cuerpo))
                .get("id").asText();
        llamar(HttpMethod.DELETE, "/api/v1/listas-bloqueo/" + id, TokensDePrueba.ADMIN, null);

        var otra = llamar(HttpMethod.POST, "/api/v1/listas-bloqueo", TokensDePrueba.ADMIN, cuerpo);

        assertThat(otra.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lista_bloqueo WHERE valor = 'dev_x'", Integer.class))
                .isEqualTo(1);
        assertThat(bloqueos.buscar(UUID.randomUUID(), "dev_x", "COM-1", Instant.now())).isPresent();
    }

    @Test
    void listas_validan_y_restringen_por_rol() {
        assertThat(llamar(HttpMethod.POST, "/api/v1/listas-bloqueo", TokensDePrueba.ANALISTA,
                "{\"tipo\": \"TARJETA\", \"valor\": \"x\", \"motivo\": \"m\"}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(llamar(HttpMethod.POST, "/api/v1/listas-bloqueo", TokensDePrueba.ADMIN,
                "{\"tipo\": \"PAIS\", \"valor\": \"x\", \"motivo\": \"m\"}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(llamar(HttpMethod.DELETE, "/api/v1/listas-bloqueo/" + UUID.randomUUID(), TokensDePrueba.ADMIN, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Consultas (lectura: ADMIN, SUPERVISOR, ANALISTA, AUDITOR) ---

    @Test
    void consulta_del_perfil_de_un_cliente() throws Exception {
        UUID cliente = UUID.randomUUID();
        redis.opsForHash().putAll("perfil:" + cliente, java.util.Map.of("n30d", "12", "promedio30d", "180.00",
                "desviacion30d", "40.00", "paises", "PE", "mccs", "5411",
                "horas", "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1", "actualizado", "1794000000000"));
        redis.opsForSet().add("dispositivos:" + cliente, "dev_a");

        var r = llamar(HttpMethod.GET, "/api/v1/clientes/" + cliente + "/perfil", TokensDePrueba.AUDITOR, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(r).get("n30d").asInt()).isEqualTo(12);
        assertThat(json(r).get("promedio30d").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(json(r).get("dispositivos")).extracting(JsonNode::asText).containsExactly("dev_a");
        assertThat(json(r).get("horas")).hasSize(24);
        assertThat(llamar(HttpMethod.GET, "/api/v1/clientes/" + UUID.randomUUID() + "/perfil", TokensDePrueba.ANALISTA,
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void consulta_de_una_evaluacion() throws Exception {
        UUID tx = UUID.randomUUID();
        String solicitud = """
                {"transaccionId": "%s", "idExterno": "%s", "tarjetaId": "%s", "clienteId": "%s",
                 "comercioId": "COM-00421", "mcc": "7995", "dispositivoHuella": "dev_nuevo",
                 "monto": 189.90, "moneda": "PEN", "canal": "POS", "pais": "PE",
                 "latitud": -12.0931, "longitud": -77.0465, "fecha": "2026-11-06T14:30:12Z"}
                """.formatted(tx, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        llamar(HttpMethod.POST, "/internal/v1/evaluaciones", null, solicitud);

        var r = llamar(HttpMethod.GET, "/api/v1/evaluaciones/" + tx, TokensDePrueba.ANALISTA, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode e = json(r);
        assertThat(e.get("transaccionId").asText()).isEqualTo(tx.toString());
        assertThat(e.get("decision").asText()).isEqualTo("REVISAR");
        assertThat(e.get("score").asInt()).isEqualTo(40);
        assertThat(e.get("reglasDisparadas")).hasSize(2);
        assertThat(e.get("versionesReglas").get("R01").asInt()).isEqualTo(1);
        assertThat(e.get("tardia").asBoolean()).isFalse();
        assertThat(llamar(HttpMethod.GET, "/api/v1/evaluaciones/" + UUID.randomUUID(), TokensDePrueba.AUDITOR, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(llamar(HttpMethod.GET, "/api/v1/evaluaciones/" + tx, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
