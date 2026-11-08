/** {@code GET /api/v1/metricas} (scoring, ADR 0017). */
export interface MetricasScoring {
  desde: string;
  hasta: string;
  evaluaciones: number;
  evaluacionesTardias: number;
  transaccionesPorSegundo: number;
  latenciaMs: { p50: number | null; p95: number | null; p99: number | null };
  deteccion: {
    fraudes: number;
    fraudesMarcados: number;
    marcadas: number;
    legitimas: number;
    legitimasMarcadas: number;
    deteccion: number | null;
    precision: number | null;
    falsosPositivos: number | null;
    montoFraudeEvitado: number;
    montoFraudeNoDetectado: number;
  };
  porEscenario: {
    escenario: string;
    fraudes: number;
    fraudesMarcados: number;
    deteccion: number | null;
    montoFraudeEvitado: number;
    montoFraudeNoDetectado: number;
  }[];
}

export interface MinutoTransacciones {
  minuto: string;
  total: number;
  aprobar: number;
  revisar: number;
  rechazar: number;
  respaldos: number;
}

/** {@code GET /api/v1/metricas/transacciones}. */
export interface MetricasTransacciones {
  desde: string;
  hasta: string;
  total: number;
  respaldos: number;
  tarjetaBloqueada: number;
  pendientes: number;
  porDecision: { APROBAR: number; REVISAR: number; RECHAZAR: number };
  porMinuto: MinutoTransacciones[];
}

/** {@code GET /api/v1/metricas/casos}. */
export interface MetricasCasos {
  desde: string;
  hasta: string;
  abiertos: number;
  abiertosPorEstado: Record<string, number>;
  abiertosPorPrioridad: { ALTA: number; MEDIA: number };
  abiertosPorAnalista: { analista: string | null; abiertos: number }[];
  cerrados: number;
  cerradosPorResolucion: Record<string, number>;
  tiempoMedioResolucionMinutos: number | null;
}

export type Ventana = '1h' | '24h' | '7d';

export const DURACION_VENTANA: Record<Ventana, number> = {
  '1h': 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
};
