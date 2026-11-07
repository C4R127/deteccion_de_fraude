package com.fraude.common.errores;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControladorPrueba())
            .setControllerAdvice(new ProblemDetailsHandler())
            .build();

    @Test
    void error_inesperado_devuelve_500_sin_filtrar_el_mensaje_interno() throws Exception {
        mvc.perform(get("/explota"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Error interno"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("secreto"))));
    }

    @Test
    void cuerpo_invalido_devuelve_422_con_los_campos() throws Exception {
        mvc.perform(post("/montos").contentType(MediaType.APPLICATION_JSON).content("{\"monto\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.errores[0].campo").value("monto"));
    }

    @Test
    void recurso_no_encontrado_devuelve_404() throws Exception {
        mvc.perform(get("/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Comercio COM-1 no existe"));
    }

    @RestController
    static class ControladorPrueba {

        @GetMapping("/explota")
        String explota() {
            throw new IllegalStateException("secreto interno");
        }

        @PostMapping("/montos")
        String montos(@Valid @RequestBody Cuerpo cuerpo) {
            return "ok";
        }

        @GetMapping("/no-existe")
        String noExiste() {
            throw new RecursoNoEncontradoException("Comercio COM-1 no existe");
        }
    }

    record Cuerpo(@Positive BigDecimal monto) {}
}
