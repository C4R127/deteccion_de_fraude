package com.fraude.transacciones.maestros;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** §10: nombre del cliente enmascarado para AUDITOR (ADR 0016). */
class EnmascaradorNombreTest {

    @Test
    void cada_palabra_conserva_su_inicial() {
        assertThat(EnmascaradorNombre.enmascarar("Juan Perez")).isEqualTo("J*** P***");
        assertThat(EnmascaradorNombre.enmascarar("María José de la Cruz")).isEqualTo("M*** J*** d*** l*** C***");
    }

    @Test
    void espacios_extra_y_vacios() {
        assertThat(EnmascaradorNombre.enmascarar("  Ana   Lucía ")).isEqualTo("A*** L***");
        assertThat(EnmascaradorNombre.enmascarar("")).isEmpty();
        assertThat(EnmascaradorNombre.enmascarar(null)).isNull();
    }
}
