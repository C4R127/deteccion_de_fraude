import { puede } from '@/auth/permisos';
import type { Sesion } from '@/auth/sesion';
import type { Caso } from './tipos';

export type AccionCaso = 'tomar' | 'asignar' | 'investigar' | 'cerrar';

/**
 * Acciones que la interfaz ofrece (§8.1, §10). Es solo para mostrar botones: la API vuelve a
 * validar estado y permisos y responde 409 o 403 si no corresponde.
 */
export function accionesDisponibles(caso: Caso, sesion: Sesion): AccionCaso[] {
  const trabaja = puede(sesion.roles, 'trabajarCasos');
  const supervisa = puede(sesion.roles, 'asignarCasos');
  const asignadoAMi = caso.analistaId === sesion.usuario;
  const acciones: AccionCaso[] = [];
  switch (caso.estado) {
    case 'NUEVO':
      if (trabaja) acciones.push('tomar');
      if (supervisa) acciones.push('asignar');
      break;
    case 'ASIGNADO':
      if (trabaja && asignadoAMi) acciones.push('investigar');
      if (supervisa) acciones.push('asignar');
      break;
    case 'EN_INVESTIGACION':
      if ((trabaja && asignadoAMi) || supervisa) acciones.push('cerrar');
      break;
    case 'CERRADO':
      break;
  }
  return acciones;
}
