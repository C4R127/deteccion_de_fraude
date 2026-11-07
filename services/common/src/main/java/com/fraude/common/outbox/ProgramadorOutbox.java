package com.fraude.transacciones.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Ejecuta el publicador del outbox periódicamente (se puede apagar en pruebas). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publicador.habilitado", havingValue = "true", matchIfMissing = true)
public class ProgramadorOutbox {

    private final PublicadorOutbox publicador;

    public ProgramadorOutbox(PublicadorOutbox publicador) {
        this.publicador = publicador;
    }

    @Scheduled(fixedDelayString = "${outbox.publicador.intervalo:200ms}")
    void publicar() {
        // Mientras haya lotes llenos, seguir sin esperar al siguiente intervalo.
        while (publicador.publicarPendientes() >= PublicadorOutbox.LOTE) {
            Thread.onSpinWait();
        }
    }

    @Scheduled(fixedDelayString = "${outbox.purga.intervalo:1h}")
    void purgar() {
        publicador.purgarEnviados();
    }
}
