package com.fraude.casos.vivo;

import com.fraude.casos.caso.Caso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Difunde los casos nuevos en {@code /topic/casos} (§8.3) cuando la transacción que los creó
 * confirma. Es un aviso a la interfaz, no un evento de negocio: si se pierde, la bandeja lo
 * muestra al recargar. Por eso no usa el outbox, que es para Kafka.
 */
@Component
public class NotificadorCasos {

    private static final Logger log = LoggerFactory.getLogger(NotificadorCasos.class);

    private final SimpMessagingTemplate mensajeria;

    public NotificadorCasos(SimpMessagingTemplate mensajeria) {
        this.mensajeria = mensajeria;
    }

    public void casoNuevo(Caso caso) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviar(caso);
                }
            });
        } else {
            enviar(caso);
        }
    }

    private void enviar(Caso caso) {
        try {
            mensajeria.convertAndSend(WebSocketConfig.DESTINO_CASOS, caso);
        } catch (RuntimeException e) {
            log.warn("No se pudo difundir el caso {} por WebSocket: {}", caso.id(), e.getMessage());
        }
    }
}
