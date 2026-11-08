package com.fraude.scoring;

import com.fraude.common.errores.ProblemDetailsHandler;
import com.fraude.common.kafka.ConsumidoresConfig;
import com.fraude.common.outbox.OutboxConfig;
import com.fraude.scoring.motor.Umbrales;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import({ProblemDetailsHandler.class, OutboxConfig.class, ConsumidoresConfig.class})
public class ScoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScoringApplication.class, args);
    }

    @Bean
    Umbrales umbrales(ScoringProperties props) {
        return props.umbrales();
    }
}
