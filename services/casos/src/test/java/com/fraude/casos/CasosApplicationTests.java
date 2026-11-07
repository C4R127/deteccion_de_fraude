package com.fraude.casos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = CasosApplication.class)
@Import({TestcontainersConfig.class, TokensDePrueba.class})
class CasosApplicationTests {

    @Test
    void contextLoads() {
    }
}
