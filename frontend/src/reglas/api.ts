import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '@/api/cliente';
import type { BloqueoNuevo, ReglaEditada } from './esquemas';
import type { EntradaLista, VersionRegla } from './tipos';

export function useReglas() {
  const api = useApi();
  return useQuery({ queryKey: ['reglas'], queryFn: () => api.get<VersionRegla[]>('/api/v1/reglas') });
}

export function useVersiones(codigo: string | null) {
  const api = useApi();
  return useQuery({
    queryKey: ['reglas', codigo, 'versiones'],
    queryFn: () => api.get<VersionRegla[]>(`/api/v1/reglas/${codigo}/versiones`),
    enabled: !!codigo,
  });
}

/** Cada edición crea una versión nueva de la regla (ADR 0014). */
export function useEditarRegla(codigo: string) {
  const api = useApi();
  const consultas = useQueryClient();
  return useMutation({
    mutationFn: (regla: ReglaEditada) => api.put<VersionRegla>(`/api/v1/reglas/${codigo}`, regla),
    onSuccess: () => consultas.invalidateQueries({ queryKey: ['reglas'] }),
  });
}

export function useListas() {
  const api = useApi();
  return useQuery({ queryKey: ['listas-bloqueo'], queryFn: () => api.get<EntradaLista[]>('/api/v1/listas-bloqueo') });
}

export function useBloquear() {
  const api = useApi();
  const consultas = useQueryClient();
  return useMutation({
    mutationFn: (bloqueo: BloqueoNuevo) => api.post<EntradaLista>('/api/v1/listas-bloqueo', bloqueo),
    onSuccess: () => consultas.invalidateQueries({ queryKey: ['listas-bloqueo'] }),
  });
}

/** DELETE vence la entrada: deja de bloquear, pero la fila se conserva (ADR 0014). */
export function useVencer() {
  const api = useApi();
  const consultas = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/v1/listas-bloqueo/${id}`),
    onSuccess: () => consultas.invalidateQueries({ queryKey: ['listas-bloqueo'] }),
  });
}
