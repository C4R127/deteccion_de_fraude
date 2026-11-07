import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import type { Rol } from '@/auth/permisos';
import { SesionContext, type Sesion } from '@/auth/sesion';

export function sesionDePrueba(roles: Rol[], usuario = 'usuario-prueba'): Sesion {
  return {
    usuario,
    roles,
    token: async () => `token-${usuario}`,
    salir: () => {},
  };
}

/** Renderiza con sesión falsa, router en memoria y TanStack Query sin reintentos. */
export function renderizar(ui: ReactNode, opciones: { roles?: Rol[]; ruta?: string; sesion?: Sesion } = {}) {
  const sesion = opciones.sesion ?? sesionDePrueba(opciones.roles ?? ['ANALISTA']);
  const consultas = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <SesionContext.Provider value={sesion}>
      <QueryClientProvider client={consultas}>
        <MemoryRouter initialEntries={[opciones.ruta ?? '/']}>{ui}</MemoryRouter>
      </QueryClientProvider>
    </SesionContext.Provider>,
  );
}
