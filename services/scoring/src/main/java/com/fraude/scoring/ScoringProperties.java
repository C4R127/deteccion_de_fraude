package com.fraude.scoring;

import com.fraude.scoring.motor.Umbrales;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del scoring. {@code tiempoLimite} debe coincidir con el de transacciones
 * (ADR 0012); {@code umbrales} son los de §7.3.
 */
@ConfigurationProperties("scoring")
public record ScoringProperties(Duration tiempoLimite, Umbrales umbrales) {
}
