package com.fraude.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = ScoringApplication.class)
@Import(TestcontainersConfig.class)
class ScoringApplicationTests {

    @Test
    void contextLoads() {
    }
}
