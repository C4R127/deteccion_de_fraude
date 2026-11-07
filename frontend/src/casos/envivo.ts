import { Client } from '@stomp/stompjs';
import { createContext, useContext, useEffect, useRef } from 'react';
import type { Sesion } from '@/auth/sesion';
import type { Caso } from './tipos';

/** Se suscribe a los casos nuevos; devuelve la función para cancelar la suscripción. */
export type SuscriptorCasos = (alRecibir: (caso: Caso) => void) => () => void;

export const CasosEnVivoContext = createContext<SuscriptorCasos | null>(null);

const DESTINO = '/topic/casos';

/**
 * STOMP sobre WebSocket por el gateway ({@code /ws}, proxy de Vite en desarrollo). El token va
 * en el frame CONNECT y se pide de nuevo en cada reconexión (Tarea 5.2).
 */
export function suscriptorStomp(sesion: Sesion): SuscriptorCasos {
  return (alRecibir) => {
    const protocolo = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const cliente = new Client({
      brokerURL: `${protocolo}://${window.location.host}/ws`,
      reconnectDelay: 5000,
      beforeConnect: async (c) => {
        c.connectHeaders = { Authorization: `Bearer ${await sesion.token()}` };
      },
      onConnect: () => {
        cliente.subscribe(DESTINO, (mensaje) => alRecibir(JSON.parse(mensaje.body) as Caso));
      },
      onStompError: (frame) => console.warn('STOMP rechazó la conexión:', frame.headers.message),
    });
    cliente.activate();
    return () => {
      void cliente.deactivate();
    };
  };
}

/** Llama a {@code alRecibir} por cada caso nuevo mientras el componente está montado. */
export function useCasosEnVivo(alRecibir: (caso: Caso) => void) {
  const suscribir = useContext(CasosEnVivoContext);
  const ultimo = useRef(alRecibir);
  ultimo.current = alRecibir;
  useEffect(() => {
    if (!suscribir) {
      return undefined;
    }
    return suscribir((caso) => ultimo.current(caso));
  }, [suscribir]);
}
