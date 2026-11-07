import type { ReactNode } from 'react';
import { Label } from '@/components/ui/label';

/** Etiqueta, control y mensaje de error de un campo de formulario. */
export function Campo({ id, etiqueta, error, ayuda, children }: {
  id: string;
  etiqueta: string;
  error?: string;
  ayuda?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{etiqueta}</Label>
      {children}
      {ayuda && !error && <p className="text-xs text-muted-foreground">{ayuda}</p>}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

export const CLASE_SELECT =
  'h-9 rounded-md border border-input bg-transparent px-3 text-sm shadow-xs focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none';
