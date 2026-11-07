package com.fraude.casos.vivo;

import com.fraude.common.seguridad.SeguridadJwt;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Autentica el WebSocket en el frame CONNECT (riesgo "WebSocket abierto en el gateway" de
 * CLAUDE.md): exige {@code Authorization: Bearer <token>} válido para la audiencia
 * {@code servicios} y un rol de lectura de casos (§10). Después solo admite suscripciones a
 * {@link WebSocketConfig#DESTINO_CASOS}; el cliente no envía mensajes. Cualquier rechazo
 * responde un frame ERROR y cierra la sesión.
 */
@Component
public class AutenticacionStomp implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AutenticacionStomp.class);
    private static final Set<String> LECTURA = Set.of("ROLE_ADMIN", "ROLE_SUPERVISOR", "ROLE_ANALISTA", "ROLE_AUDITOR");
    private static final String PREFIJO = "Bearer ";

    private final JwtDecoder decodificador;
    private final JwtAuthenticationConverter convertidor = SeguridadJwt.convertidor();

    public AutenticacionStomp(JwtDecoder decodificador) {
        this.decodificador = decodificador;
    }

    @Override
    public Message<?> preSend(Message<?> mensaje, MessageChannel canal) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(mensaje, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return mensaje;
        }
        switch (stomp.getCommand()) {
            case CONNECT -> stomp.setUser(autenticar(stomp));
            case SUBSCRIBE -> {
                exigirSesion(stomp);
                if (!WebSocketConfig.DESTINO_CASOS.equals(stomp.getDestination())) {
                    throw new MessageDeliveryException("Solo se permite suscribirse a " + WebSocketConfig.DESTINO_CASOS);
                }
            }
            case SEND -> throw new MessageDeliveryException("Este WebSocket solo envía casos; no recibe mensajes");
            default -> {
            }
        }
        return mensaje;
    }

    private AbstractAuthenticationToken autenticar(StompHeaderAccessor stomp) {
        String cabecera = stomp.getFirstNativeHeader("Authorization");
        if (cabecera == null || !cabecera.startsWith(PREFIJO)) {
            throw new MessageDeliveryException("CONNECT sin token Bearer");
        }
        AbstractAuthenticationToken autenticacion;
        try {
            autenticacion = convertidor.convert(decodificador.decode(cabecera.substring(PREFIJO.length()).strip()));
        } catch (JwtException e) {
            log.info("CONNECT rechazado: {}", e.getMessage());
            throw new MessageDeliveryException("Token inválido");
        }
        boolean lectura = autenticacion.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(LECTURA::contains);
        if (!lectura) {
            throw new MessageDeliveryException("El rol no permite ver casos");
        }
        return autenticacion;
    }

    private static void exigirSesion(StompHeaderAccessor stomp) {
        if (stomp.getUser() == null) {
            throw new MessageDeliveryException("Sesión no autenticada");
        }
    }
}
