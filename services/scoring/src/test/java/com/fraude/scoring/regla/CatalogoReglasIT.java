package com.fraude.scoring.regla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraude.scoring.TestcontainersConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class CatalogoReglasIT {

    @Autowired
    CatalogoReglas catalogo;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void restaurarReglasIniciales() {
        jdbc.update("DELETE FROM regla WHERE version > 1");
        jdbc.update("UPDATE regla SET vigente = true");
        catalogo.recargar();
    }

    private int vigentes(String codigo) {
        return jdbc.queryForObject("SELECT count(*) FROM regla WHERE codigo = ? AND vigente", Integer.class, codigo);
    }

    @Test
    void la_migracion_carga_r01_a_r07_en_version_1() {
        assertThat(catalogo.vigentes()).extracting(ReglaVigente::codigo)
                .containsExactly("R01", "R02", "R03", "R04", "R05", "R06", "R07");
        assertThat(catalogo.vigentes()).allSatisfy(r -> {
            assertThat(r.version()).isEqualTo(1);
            assertThat(r.activa()).isTrue();
        });
        assertThat(catalogo.vigente("R01").puntos()).isEqualTo(40);
        assertThat(catalogo.vigente("R03").puntos()).isEqualTo(50);
        assertThat(catalogo.vigente("R07").puntos()).isEqualTo(10);
    }

    @Test
    void los_parametros_jsonb_se_leen_como_mapa_tipado() {
        Parametros r01 = catalogo.vigente("R01").parametros();
        Parametros r03 = catalogo.vigente("R03").parametros();
        Parametros r06 = catalogo.vigente("R06").parametros();

        assertThat(r01.decimal("factor")).isEqualByComparingTo("5");
        assertThat(r01.entero("minimoTransacciones")).isEqualTo(10);
        assertThat(r03.entero("velocidadMaximaKmH")).isEqualTo(800);
        assertThat(r06.textos("mccs")).containsExactly("7995", "6051", "4829");
        assertThatThrownBy(() -> r01.entero("noExiste"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noExiste");
    }

    @Test
    void editar_una_regla_crea_version_2_y_deja_una_sola_vigente() {
        var edicion = new EdicionRegla(null, null, Map.of("factor", 4, "minimoTransacciones", 10), 45, null);

        ReglaVigente nueva = catalogo.editar("R01", edicion, "supervisor");

        assertThat(nueva.version()).isEqualTo(2);
        assertThat(vigentes("R01")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regla WHERE codigo = 'R01'", Integer.class)).isEqualTo(2);
        var anterior = jdbc.queryForMap("SELECT puntos, vigente FROM regla WHERE codigo = 'R01' AND version = 1");
        assertThat(anterior).containsEntry("puntos", 40).containsEntry("vigente", false);
        assertThat(jdbc.queryForObject("SELECT creada_por FROM regla WHERE codigo = 'R01' AND version = 2",
                String.class)).isEqualTo("supervisor");
    }

    @Test
    void el_catalogo_devuelve_la_version_nueva_tras_editar() {
        catalogo.editar("R01", new EdicionRegla(null, null, Map.of("factor", 4, "minimoTransacciones", 10), 45,
                null), "supervisor");

        ReglaVigente r01 = catalogo.vigente("R01");
        assertThat(r01.version()).isEqualTo(2);
        assertThat(r01.puntos()).isEqualTo(45);
        assertThat(r01.parametros().decimal("factor")).isEqualByComparingTo(new BigDecimal("4"));
    }

    @Test
    void lo_no_editado_se_conserva_de_la_version_anterior() {
        catalogo.editar("R06", new EdicionRegla(null, null, null, null, false), "admin");

        ReglaVigente r06 = catalogo.vigente("R06");
        assertThat(r06.activa()).isFalse();
        assertThat(r06.puntos()).isEqualTo(15);
        assertThat(r06.nombre()).isEqualTo("Rubro de riesgo");
        assertThat(r06.parametros().textos("mccs")).isEqualTo(List.of("7995", "6051", "4829"));
    }

    @Test
    void dos_ediciones_seguidas_llegan_a_la_version_3() {
        catalogo.editar("R02", new EdicionRegla(null, null, null, 35, null), "admin");
        ReglaVigente v3 = catalogo.editar("R02", new EdicionRegla(null, null, null, 30, null), "admin");

        assertThat(v3.version()).isEqualTo(3);
        assertThat(vigentes("R02")).isEqualTo(1);
    }

    @Test
    void la_base_de_datos_impide_dos_filas_vigentes_del_mismo_codigo() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO regla (id, codigo, nombre, descripcion, parametros, puntos, activa, version, vigente,
                                   creada_en, creada_por)
                VALUES (gen_random_uuid(), 'R01', 'x', 'x', '{}', 1, true, 9, true, now(), 'x')
                """)).hasMessageContaining("regla_vigente_unica");
    }

    @Test
    void evaluacion_solo_admite_insert() {
        jdbc.update("""
                INSERT INTO evaluacion (id, transaccion_id, id_externo, monto, score, decision, reglas_disparadas,
                                        versiones_reglas, latencia_ms, creada_en)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 10.00, 0, 'APROBAR', '[]', '{}', 1,
                        now())
                """);

        assertThatThrownBy(() -> jdbc.update("UPDATE evaluacion SET score = 99"))
                .hasMessageContaining("solo admite INSERT");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM evaluacion"))
                .hasMessageContaining("solo admite INSERT");
    }
}
