import { Badge } from '@/components/ui/badge';
import { ETIQUETA_ESTADO } from '@/lib/formato';
import { cn } from '@/lib/utils';
import type { EstadoCaso, Prioridad } from './tipos';

export function InsigniaPrioridad({ prioridad }: { prioridad: Prioridad }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'font-medium',
        prioridad === 'ALTA'
          ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300'
          : 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-300',
      )}
    >
      {prioridad}
    </Badge>
  );
}

const COLOR_ESTADO: Record<EstadoCaso, string> = {
  NUEVO: 'bg-sky-50 text-sky-800 border-sky-200 dark:bg-sky-950 dark:text-sky-300 dark:border-sky-900',
  ASIGNADO: 'bg-violet-50 text-violet-800 border-violet-200 dark:bg-violet-950 dark:text-violet-300 dark:border-violet-900',
  EN_INVESTIGACION: 'bg-orange-50 text-orange-800 border-orange-200 dark:bg-orange-950 dark:text-orange-300 dark:border-orange-900',
  CERRADO: 'bg-muted text-muted-foreground',
};

export function InsigniaEstado({ estado }: { estado: EstadoCaso }) {
  return (
    <Badge variant="outline" className={cn('font-medium', COLOR_ESTADO[estado])}>
      {ETIQUETA_ESTADO[estado]}
    </Badge>
  );
}
