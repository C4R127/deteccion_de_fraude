import { BarChart3, Gauge, ListX, ShieldAlert, SlidersHorizontal, type LucideIcon } from 'lucide-react';
import type { Permiso } from '@/auth/permisos';

export interface EntradaMenu {
  ruta: string;
  etiqueta: string;
  icono: LucideIcon;
  permiso: Permiso;
}

/** Menú principal; cada entrada se muestra solo a quien tiene su permiso (§10). */
export const MENU: EntradaMenu[] = [
  { ruta: '/dashboard', etiqueta: 'Dashboard', icono: Gauge, permiso: 'ver' },
  { ruta: '/casos', etiqueta: 'Casos', icono: ShieldAlert, permiso: 'ver' },
  { ruta: '/reglas', etiqueta: 'Reglas', icono: SlidersHorizontal, permiso: 'gestionarReglas' },
  { ruta: '/listas-bloqueo', etiqueta: 'Listas de bloqueo', icono: ListX, permiso: 'gestionarListas' },
  { ruta: '/metricas', etiqueta: 'Métricas', icono: BarChart3, permiso: 'ver' },
];
