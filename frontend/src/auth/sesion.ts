import { createContext, useContext } from 'react';
import type { Rol } from './permisos';

/** Usuario autenticado. En producción la crea Keycloak; en pruebas, una sesión falsa. */
export interface Sesion {
  usuario: string;
  roles: Rol[];
  /** Token de acceso vigente (lo renueva si está por vencer). */
  token: () => Promise<string>;
  salir: () => void;
}

export const SesionContext = createContext<Sesion | null>(null);

export function useSesion(): Sesion {
  const sesion = useContext(SesionContext);
  if (sesion === null) {
    throw new Error('useSesion debe usarse dentro de SesionContext');
  }
  return sesion;
}
