import { useMemo } from 'react';
import { useSesion, type Sesion } from '@/auth/sesion';

/** Error de la API con el detalle de su Problem Details (RFC 7807). */
export class ErrorApi extends Error {
  constructor(
    readonly status: number,
    mensaje: string,
    readonly problema?: Record<string, unknown>,
  ) {
    super(mensaje);
    this.name = 'ErrorApi';
  }
}

export interface ClienteApi {
  get<T>(ruta: string): Promise<T>;
  post<T>(ruta: string, cuerpo?: unknown): Promise<T>;
  put<T>(ruta: string, cuerpo?: unknown): Promise<T>;
  delete<T>(ruta: string): Promise<T>;
}

/** Cliente HTTP que agrega el token de la sesión; las rutas son relativas (proxy de Vite al gateway). */
export function crearClienteApi(sesion: Sesion): ClienteApi {
  async function llamar<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
    const cabeceras = new Headers({ Authorization: `Bearer ${await sesion.token()}`, Accept: 'application/json' });
    if (cuerpo !== undefined) {
      cabeceras.set('Content-Type', 'application/json');
    }
    const respuesta = await fetch(ruta, {
      method: metodo,
      headers: cabeceras,
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    });
    const texto = await respuesta.text();
    const datos: unknown = texto ? JSON.parse(texto) : undefined;
    if (!respuesta.ok) {
      const problema = (datos ?? {}) as Record<string, unknown>;
      const mensaje = typeof problema.detail === 'string' ? problema.detail : `Error HTTP ${respuesta.status}`;
      throw new ErrorApi(respuesta.status, mensaje, problema);
    }
    return datos as T;
  }

  return {
    get: (ruta) => llamar('GET', ruta),
    post: (ruta, cuerpo) => llamar('POST', ruta, cuerpo),
    put: (ruta, cuerpo) => llamar('PUT', ruta, cuerpo),
    delete: (ruta) => llamar('DELETE', ruta),
  };
}

export function useApi(): ClienteApi {
  const sesion = useSesion();
  return useMemo(() => crearClienteApi(sesion), [sesion]);
}
