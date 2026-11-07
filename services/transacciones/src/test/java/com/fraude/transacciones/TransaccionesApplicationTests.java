package com.fraude.transacciones;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = TransaccionesApplication.class)
@Import(TestcontainersConfig.class)
class TransaccionesApplicationTests {

    @Test
    void contextLoads() {
    }
}
