import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router';
import { puede, type Permiso } from '@/auth/permisos';
import { useSesion } from '@/auth/sesion';
import { PaginaCasos } from '@/casos/PaginaCasos';
import { PaginaDetalleCaso } from '@/casos/PaginaDetalleCaso';
import { Layout } from '@/layout/Layout';
import { Pagina, Pendiente } from '@/paginas/Pagina';

/** Muestra la página solo si la sesión tiene el permiso; si no, un aviso (la API también lo rechaza). */
function RequierePermiso({ permiso, children }: { permiso: Permiso; children: ReactNode }) {
  const { roles } = useSesion();
  if (!puede(roles, permiso)) {
    return <Pagina titulo="Sin permiso" descripcion="Tu rol no permite abrir esta sección." />;
  }
  return children;
}

function SinAcceso() {
  const { usuario, salir } = useSesion();
  return (
    <main className="mx-auto max-w-md px-6 py-24 text-center">
      <h1 className="text-2xl font-semibold">Sin acceso</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        El usuario {usuario} no tiene un rol de la aplicación (ADMIN, SUPERVISOR, ANALISTA o AUDITOR).
      </p>
      <button className="mt-6 text-sm underline" onClick={salir}>
        Cerrar sesión
      </button>
    </main>
  );
}

/** Rutas de §5.3 del plan: /dashboard, /casos, /casos/:id, /reglas, /listas-bloqueo, /metricas. */
export function RutasApp() {
  const { roles } = useSesion();
  if (!puede(roles, 'ver')) {
    return <SinAcceso />;
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Pendiente titulo="Dashboard" tarea="Fase 6" />} />
        <Route path="/casos" element={<PaginaCasos />} />
        <Route path="/casos/:id" element={<PaginaDetalleCaso />} />
        <Route
          path="/reglas"
          element={
            <RequierePermiso permiso="gestionarReglas">
              <Pendiente titulo="Reglas" tarea="Tarea 5.5" />
            </RequierePermiso>
          }
        />
        <Route
          path="/listas-bloqueo"
          element={
            <RequierePermiso permiso="gestionarListas">
              <Pendiente titulo="Listas de bloqueo" tarea="Tarea 5.5" />
            </RequierePermiso>
          }
        />
        <Route path="/metricas" element={<Pendiente titulo="Métricas" tarea="Fase 6" />} />
        <Route path="*" element={<Pagina titulo="No encontrado" descripcion="La página no existe." />} />
      </Route>
    </Routes>
  );
}
