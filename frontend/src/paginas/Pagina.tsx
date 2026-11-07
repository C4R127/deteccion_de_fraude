import type { ReactNode } from 'react';

/** Encabezado común de las páginas. */
export function Pagina({ titulo, descripcion, children }: { titulo: string; descripcion?: string; children?: ReactNode }) {
  return (
    <section className="flex flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">{titulo}</h1>
        {descripcion && <p className="mt-1 text-sm text-muted-foreground">{descripcion}</p>}
      </header>
      {children}
    </section>
  );
}

/** Contenido pendiente de una fase posterior del plan. */
export function Pendiente({ titulo, tarea }: { titulo: string; tarea: string }) {
  return <Pagina titulo={titulo} descripcion={`Se construye en la ${tarea} del plan.`} />;
}
