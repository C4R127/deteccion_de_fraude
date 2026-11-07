package com.fraude.casos;

import com.fraude.common.errores.ProblemDetailsHandler;
import com.fraude.common.outbox.OutboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ProblemDetailsHandler.class, OutboxConfig.class})
public class CasosApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasosApplication.class, args);
    }
}
