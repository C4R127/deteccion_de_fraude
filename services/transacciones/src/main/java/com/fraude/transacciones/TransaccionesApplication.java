package com.fraude.transacciones;

import com.fraude.common.errores.ProblemDetailsHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(ProblemDetailsHandler.class)
public class TransaccionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransaccionesApplication.class, args);
    }
}
