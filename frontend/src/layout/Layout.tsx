import { LogOut } from 'lucide-react';
import { NavLink, Outlet } from 'react-router';
import { puede } from '@/auth/permisos';
import { useSesion } from '@/auth/sesion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { MENU } from './navegacion';

/** Estructura de la aplicación: menú lateral según el rol y la página actual. */
export function Layout() {
  const sesion = useSesion();
  const entradas = MENU.filter((entrada) => puede(sesion.roles, entrada.permiso));

  return (
    <div className="flex min-h-screen bg-background">
      <aside className="flex w-60 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground">
        <div className="px-5 py-5">
          <p className="text-sm font-semibold tracking-tight">Detección de fraude</p>
          <p className="text-xs text-muted-foreground">Datos ficticios</p>
        </div>
        <nav aria-label="Menú principal" className="flex flex-1 flex-col gap-1 px-3">
          {entradas.map(({ ruta, etiqueta, icono: Icono }) => (
            <NavLink
              key={ruta}
              to={ruta}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'bg-sidebar-accent font-medium text-sidebar-accent-foreground'
                    : 'text-muted-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                )
              }
            >
              <Icono className="size-4" aria-hidden />
              {etiqueta}
            </NavLink>
          ))}
        </nav>
        <div className="border-t px-5 py-4">
          <p className="truncate text-sm font-medium">{sesion.usuario}</p>
          <div className="mt-1 flex flex-wrap gap-1">
            {sesion.roles.map((rol) => (
              <Badge key={rol} variant="secondary">
                {rol}
              </Badge>
            ))}
          </div>
          <Button variant="ghost" size="sm" className="mt-3 w-full justify-start px-2" onClick={sesion.salir}>
            <LogOut className="size-4" aria-hidden />
            Cerrar sesión
          </Button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 px-8 py-6">
        <Outlet />
      </main>
    </div>
  );
}
