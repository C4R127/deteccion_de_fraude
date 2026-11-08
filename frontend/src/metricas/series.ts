import type { MinutoTransacciones } from './tipos';

const MINUTO = 60_000;

/** La API solo devuelve los minutos con transacciones; el gráfico necesita los vacíos en 0. */
export function completarMinutos(porMinuto: MinutoTransacciones[], desde: string, hasta: string): MinutoTransacciones[] {
  const conDatos = new Map(porMinuto.map((m) => [new Date(m.minuto).getTime(), m]));
  const inicio = Math.floor(new Date(desde).getTime() / MINUTO) * MINUTO;
  const fin = new Date(hasta).getTime();
  const serie: MinutoTransacciones[] = [];
  for (let t = inicio; t < fin; t += MINUTO) {
    const m = conDatos.get(t);
    serie.push({
      minuto: new Date(t).toISOString(),
      total: m?.total ?? 0,
      aprobar: m?.aprobar ?? 0,
      revisar: m?.revisar ?? 0,
      rechazar: m?.rechazar ?? 0,
      respaldos: m?.respaldos ?? 0,
    });
  }
  return serie;
}

const entero = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 0 });
const unDecimal = new Intl.NumberFormat('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

export function numero(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '—' : entero.format(valor);
}

/** Porcentaje 0–100 con un decimal; nulo (sin denominador) se muestra como guion. */
export function porcentaje(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '—' : `${unDecimal.format(valor)} %`;
}

export function milisegundos(valor: number | null | undefined): string {
  return valor === null || valor === undefined ? '—' : `${entero.format(valor)} ms`;
}

/** Colores de estado (fijos): la decisión es un estado, no una serie categórica. */
export const COLOR_DECISION = {
  APROBAR: '#0ca30c',
  REVISAR: '#fab219',
  RECHAZAR: '#d03b3b',
} as const;

export const COLOR_SERIE = '#2a78d6';

export const TINTA = {
  primaria: '#0b0b0b',
  secundaria: '#52514e',
  apagada: '#898781',
  rejilla: '#e1e0d9',
  eje: '#c3c2b7',
  superficie: '#fcfcfb',
} as const;
