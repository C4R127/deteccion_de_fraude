import { useQuery } from '@tanstack/react-query';
import { useApi } from '@/api/cliente';
import { DURACION_VENTANA, type MetricasCasos, type MetricasScoring, type MetricasTransacciones, type Ventana } from './tipos';

/** El dashboard se refresca solo para seguir el tráfico del generador en vivo. */
export const REFRESCO_MS = 10_000;

function rango(ventana: Ventana): string {
  // Redondeado al minuto: la misma ventana para scoring y transacciones y una clave de caché estable.
  const hasta = new Date(Math.ceil(Date.now() / 60_000) * 60_000);
  const desde = new Date(hasta.getTime() - DURACION_VENTANA[ventana]);
  return `desde=${desde.toISOString()}&hasta=${hasta.toISOString()}`;
}

/** Cada servicio entrega sus métricas (ADR 0017); si uno falla, el resto se sigue mostrando. */
export function useMetricasScoring(ventana: Ventana) {
  const api = useApi();
  return useQuery({
    queryKey: ['metricas', 'scoring', ventana],
    queryFn: () => api.get<MetricasScoring>(`/api/v1/metricas?${rango(ventana)}`),
    refetchInterval: REFRESCO_MS,
  });
}

export function useMetricasTransacciones(ventana: Ventana) {
  const api = useApi();
  return useQuery({
    queryKey: ['metricas', 'transacciones', ventana],
    queryFn: () => api.get<MetricasTransacciones>(`/api/v1/metricas/transacciones?${rango(ventana)}`),
    refetchInterval: REFRESCO_MS,
  });
}

export function useMetricasCasos(ventana: Ventana) {
  const api = useApi();
  return useQuery({
    queryKey: ['metricas', 'casos', ventana],
    queryFn: () => api.get<MetricasCasos>(`/api/v1/metricas/casos?${rango(ventana)}`),
    refetchInterval: REFRESCO_MS,
  });
}
