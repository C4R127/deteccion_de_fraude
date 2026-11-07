package com.fraude.casos.vivo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP sobre WebSocket en {@code /ws} (§8.3): los casos nuevos se difunden en
 * {@code /topic/casos}. El navegador no puede enviar cabeceras en el handshake, así que el
 * token se valida en el frame CONNECT ({@link AutenticacionStomp}).
 *
 * <p>Broker simple en memoria: sirve con una sola instancia de casos (MVP).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String DESTINO_CASOS = "/topic/casos";

    private final AutenticacionStomp autenticacion;
    private final String[] origenesPermitidos;

    public WebSocketConfig(AutenticacionStomp autenticacion,
            @Value("${casos.ws.origenes-permitidos:http://localhost:5173}") String[] origenesPermitidos) {
        this.autenticacion = autenticacion;
        this.origenesPermitidos = origenesPermitidos;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registro) {
        registro.addEndpoint("/ws").setAllowedOriginPatterns(origenesPermitidos);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registro) {
        registro.enableSimpleBroker("/topic");
        registro.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registro) {
        registro.interceptors(autenticacion);
    }
}
