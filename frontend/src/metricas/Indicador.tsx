import type { ReactNode } from 'react';

/** Número principal con su etiqueta y un detalle opcional (stat tile). */
export function Indicador({ nombre, valor, detalle }: { nombre: string; valor: ReactNode; detalle?: ReactNode }) {
  return (
    <div role="group" aria-label={nombre} className="rounded-lg border bg-card px-4 py-3">
      <p className="text-xs font-medium text-muted-foreground">{nombre}</p>
      <p className="mt-1 text-2xl font-semibold tracking-tight tabular-nums">{valor}</p>
      {detalle && <p className="mt-0.5 text-xs text-muted-foreground">{detalle}</p>}
    </div>
  );
}
