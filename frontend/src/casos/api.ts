import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ErrorApi, useApi } from '@/api/cliente';
import { inicioDelDiaEnLima } from '@/lib/formato';
import type { AccionCaso } from './acciones';
import type { CasoDetalle, Cliente, FiltrosCasos, Nota, PaginaCasos, Perfil, TransaccionResumen } from './tipos';

export const TAMANO_PAGINA = 50;

export function rutaBandeja(filtros: FiltrosCasos, pagina: number): string {
  const params = new URLSearchParams();
  if (filtros.estado) params.set('estado', filtros.estado);
  if (filtros.prioridad) params.set('prioridad', filtros.prioridad);
  if (filtros.analista?.trim()) params.set('analista', filtros.analista.trim());
  if (filtros.desde) params.set('desde', inicioDelDiaEnLima(filtros.desde));
  if (filtros.hasta) params.set('hasta', inicioDelDiaEnLima(filtros.hasta, 1));
  params.set('pagina', String(pagina));
  params.set('tamano', String(TAMANO_PAGINA));
  return `/api/v1/casos?${params.toString()}`;
}

export function useBandeja(filtros: FiltrosCasos, pagina: number) {
  const api = useApi();
  return useQuery({
    queryKey: ['casos', 'bandeja', filtros, pagina],
    queryFn: () => api.get<PaginaCasos>(rutaBandeja(filtros, pagina)),
  });
}

export function useCaso(id: string) {
  const api = useApi();
  return useQuery({ queryKey: ['casos', 'detalle', id], queryFn: () => api.get<CasoDetalle>(`/api/v1/casos/${id}`) });
}

export function useCliente(clienteId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['clientes', clienteId],
    queryFn: () => api.get<Cliente>(`/api/v1/clientes/${clienteId}`),
    enabled: !!clienteId,
  });
}

export function useTransaccionesCliente(clienteId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['clientes', clienteId, 'transacciones'],
    queryFn: () => api.get<TransaccionResumen[]>(`/api/v1/clientes/${clienteId}/transacciones?limite=20`),
    enabled: !!clienteId,
  });
}

/** Perfil de Valkey; un cliente sin perfil responde 404 y se muestra como "sin perfil". */
export function usePerfil(clienteId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['clientes', clienteId, 'perfil'],
    queryFn: async () => {
      try {
        return await api.get<Perfil>(`/api/v1/clientes/${clienteId}/perfil`);
      } catch (error) {
        if (error instanceof ErrorApi && error.status === 404) {
          return null;
        }
        throw error;
      }
    },
    enabled: !!clienteId,
  });
}

export interface CuerpoAccion {
  analistaId?: string;
  resolucion?: string;
  comentario?: string;
}

/** Ejecuta una transición y refresca el detalle y la bandeja. */
export function useAccionCaso(id: string) {
  const api = useApi();
  const consultas = useQueryClient();
  return useMutation({
    mutationFn: ({ accion, cuerpo }: { accion: AccionCaso; cuerpo?: CuerpoAccion }) =>
      api.post<CasoDetalle>(`/api/v1/casos/${id}/${accion}`, cuerpo),
    onSuccess: () => consultas.invalidateQueries({ queryKey: ['casos'] }),
  });
}

export function useAgregarNota(id: string) {
  const api = useApi();
  const consultas = useQueryClient();
  return useMutation({
    mutationFn: (texto: string) => api.post<Nota>(`/api/v1/casos/${id}/notas`, { texto }),
    onSuccess: () => consultas.invalidateQueries({ queryKey: ['casos', 'detalle', id] }),
  });
}
