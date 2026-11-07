/** Formatos de la interfaz: hora de Lima y soles (CLAUDE.md: la zona America/Lima solo en la interfaz). */
export const ZONA = 'America/Lima';

const fechaHora = new Intl.DateTimeFormat('es-PE', {
  timeZone: ZONA,
  dateStyle: 'short',
  timeStyle: 'medium',
});

const dia = new Intl.DateTimeFormat('es-PE', { timeZone: ZONA, dateStyle: 'medium' });

const soles =new Intl.NumberFormat('es-PE', { style: 'currency', currency: 'PEN' });

export function formatearFecha(iso: string | null | undefined): string {
  return iso ? fechaHora.format(new Date(iso)) : '—';
}

export function formatearDia(iso: string | null | undefined): string {
  return iso ? dia.format(new Date(iso)) : '—';
}

export function formatearMonto(monto: number): string {
  return soles.format(monto);
}

/**
 * Inicio del día {@code yyyy-mm-dd} en Lima, más {@code dias}, como instante UTC ISO. Lima es
 * UTC-5 todo el año (sin horario de verano).
 */
export function inicioDelDiaEnLima(fecha: string, dias = 0): string {
  const [anio, mes, dia] = fecha.split('-').map(Number);
  return new Date(Date.UTC(anio!, mes! - 1, dia! + dias, 5, 0, 0)).toISOString();
}

export const ETIQUETA_ESTADO: Record<string, string> = {
  NUEVO: 'Nuevo',
  ASIGNADO: 'Asignado',
  EN_INVESTIGACION: 'En investigación',
  CERRADO: 'Cerrado',
};

export const ETIQUETA_RESOLUCION: Record<string, string> = {
  FRAUDE_CONFIRMADO: 'Fraude confirmado',
  FALSO_POSITIVO: 'Falso positivo',
};
