package com.fraude.transacciones.scoring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexión con el scoring. {@code tiempoLimite} es el máximo que se espera la decisión
 * antes de aplicar el respaldo (§3 paso 4, inicial 200 ms).
 */
@ConfigurationProperties("scoring")
public record ScoringProperties(String url, Duration tiempoLimite) {
}
