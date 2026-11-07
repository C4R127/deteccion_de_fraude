import { createColumnHelper, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import { Radio } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { formatearFecha, formatearMonto, inicioDelDiaEnLima, ETIQUETA_ESTADO } from '@/lib/formato';
import { cn } from '@/lib/utils';
import { Pagina } from '@/paginas/Pagina';
import { TAMANO_PAGINA, useBandeja } from './api';
import { useCasosEnVivo } from './envivo';
import { InsigniaEstado, InsigniaPrioridad } from './insignias';
import type { Caso, EstadoCaso, FiltrosCasos, Prioridad } from './tipos';

const ESTADOS: EstadoCaso[] = ['NUEVO', 'ASIGNADO', 'EN_INVESTIGACION', 'CERRADO'];
const PRIORIDADES: Prioridad[] = ['ALTA', 'MEDIA'];

/** Mismo orden que la API (§8.3): ALTA primero, luego los más antiguos. */
function ordenar(casos: Caso[]): Caso[] {
  return [...casos].sort((a, b) =>
    a.prioridad !== b.prioridad ? (a.prioridad === 'ALTA' ? -1 : 1) : a.creadoEn.localeCompare(b.creadoEn));
}

/** Un caso que llega en vivo se muestra solo si cumple los filtros activos. */
function cumpleFiltros(caso: Caso, f: FiltrosCasos): boolean {
  if (f.estado && caso.estado !== f.estado) return false;
  if (f.prioridad && caso.prioridad !== f.prioridad) return false;
  if (f.analista?.trim() && caso.analistaId !== f.analista.trim()) return false;
  if (f.desde && caso.creadoEn < inicioDelDiaEnLima(f.desde)) return false;
  if (f.hasta && caso.creadoEn >= inicioDelDiaEnLima(f.hasta, 1)) return false;
  return true;
}

const columna = createColumnHelper<Caso>();

function columnas(enVivo: Set<string>) {
  return [
    columna.accessor('prioridad', {
      header: 'Prioridad',
      cell: (c) => <InsigniaPrioridad prioridad={c.getValue()} />,
    }),
    columna.accessor('estado', { header: 'Estado', cell: (c) => <InsigniaEstado estado={c.getValue()} /> }),
    columna.accessor('score', { header: 'Score', cell: (c) => <span className="tabular-nums">{c.getValue()}</span> }),
    columna.accessor('monto', {
      header: 'Monto',
      cell: (c) => <span className="tabular-nums">{formatearMonto(c.getValue())}</span>,
    }),
    columna.accessor('reglasDisparadas', {
      header: 'Reglas',
      cell: (c) => c.getValue().map((r) => r.codigo).join(' · '),
    }),
    columna.accessor('analistaId', { header: 'Analista', cell: (c) => c.getValue() ?? '—' }),
    columna.accessor('creadoEn', {
      header: 'Creado (Lima)',
      cell: (c) => (
        <span className="flex items-center gap-2 whitespace-nowrap">
          {formatearFecha(c.getValue())}
          {enVivo.has(c.row.original.id) && (
            <Badge variant="secondary" className="gap-1">
              <Radio className="size-3" aria-hidden />
              En vivo
            </Badge>
          )}
        </span>
      ),
    }),
    columna.display({
      id: 'abrir',
      header: () => <span className="sr-only">Abrir</span>,
      cell: (c) => (
        <Link to={`/casos/${c.row.original.id}`} className="text-sm font-medium underline-offset-4 hover:underline">
          Ver
        </Link>
      ),
    }),
  ];
}

function Selector<T extends string>({ id, etiqueta, valor, opciones, nombres, alCambiar }: {
  id: string; etiqueta: string; valor: T | undefined; opciones: T[]; nombres?: Record<string, string>;
  alCambiar: (v: T | undefined) => void;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{etiqueta}</Label>
      <select
        id={id}
        value={valor ?? ''}
        onChange={(e) => alCambiar((e.target.value || undefined) as T | undefined)}
        className="h-9 rounded-md border border-input bg-transparent px-3 text-sm shadow-xs focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <option value="">Todos</option>
        {opciones.map((o) => (
          <option key={o} value={o}>
            {nombres?.[o] ?? o}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Bandeja de casos con filtros (§8.3) y casos nuevos en vivo por STOMP. */
export function PaginaCasos({ filtrosIniciales = {} }: { filtrosIniciales?: FiltrosCasos }) {
  const [filtros, setFiltros] = useState<FiltrosCasos>(filtrosIniciales);
  const [pagina, setPagina] = useState(0);
  const [enVivo, setEnVivo] = useState<Caso[]>([]);
  const bandeja = useBandeja(filtros, pagina);

  // Los casos en vivo se agregan a la primera página; al cambiar filtros o página se descartan
  // porque la nueva consulta ya los trae.
  useEffect(() => setEnVivo([]), [filtros, pagina]);
  useCasosEnVivo((caso) => {
    if (pagina === 0 && cumpleFiltros(caso, filtros)) {
      setEnVivo((previos) => [caso, ...previos.filter((c) => c.id !== caso.id)]);
    }
  });

  // TanStack Table necesita referencias estables de datos y columnas: si cambian en cada render,
  // reinicia su estado con setState y entra en un ciclo de renders que nunca deja terminar la
  // transición de navegación de React Router (el enlace "Ver" cambiaba la URL pero no la página).
  const { filas, idsEnVivo } = useMemo(() => {
    const deApi = bandeja.data?.contenido ?? [];
    const idsApi = new Set(deApi.map((c) => c.id));
    const soloEnVivo = enVivo.filter((c) => !idsApi.has(c.id));
    return { filas: ordenar([...soloEnVivo, ...deApi]), idsEnVivo: new Set(soloEnVivo.map((c) => c.id)) };
  }, [bandeja.data, enVivo]);
  const columnasTabla = useMemo(() => columnas(idsEnVivo), [idsEnVivo]);
  const total = (bandeja.data?.total ?? 0) + idsEnVivo.size;
  const paginas = Math.max(1, Math.ceil((bandeja.data?.total ?? 0) / TAMANO_PAGINA));

  const tabla = useReactTable({ data: filas, columns: columnasTabla, getCoreRowModel: getCoreRowModel() });

  function cambiar(cambio: Partial<FiltrosCasos>) {
    setFiltros((f) => ({ ...f, ...cambio }));
    setPagina(0);
  }

  return (
    <Pagina titulo="Casos" descripcion="Prioridad ALTA primero y, dentro de cada prioridad, los más antiguos.">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
        <Selector id="filtro-estado" etiqueta="Estado" valor={filtros.estado} opciones={ESTADOS}
          nombres={ETIQUETA_ESTADO} alCambiar={(estado) => cambiar({ estado })} />
        <Selector id="filtro-prioridad" etiqueta="Prioridad" valor={filtros.prioridad} opciones={PRIORIDADES}
          alCambiar={(prioridad) => cambiar({ prioridad })} />
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="filtro-analista">Analista</Label>
          <Input id="filtro-analista" placeholder="usuario" value={filtros.analista ?? ''}
            onChange={(e) => cambiar({ analista: e.target.value || undefined })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="filtro-desde">Desde</Label>
          <Input id="filtro-desde" type="date" value={filtros.desde ?? ''}
            onChange={(e) => cambiar({ desde: e.target.value || undefined })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="filtro-hasta">Hasta</Label>
          <Input id="filtro-hasta" type="date" value={filtros.hasta ?? ''}
            onChange={(e) => cambiar({ hasta: e.target.value || undefined })} />
        </div>
      </div>

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{total === 1 ? '1 caso' : `${total} casos`}</span>
        {bandeja.isFetching && <span>Actualizando…</span>}
      </div>

      {bandeja.isError ? (
        <p className="text-sm text-destructive">No se pudo cargar la bandeja: {bandeja.error.message}</p>
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              {tabla.getHeaderGroups().map((grupo) => (
                <TableRow key={grupo.id}>
                  {grupo.headers.map((h) => (
                    <TableHead key={h.id}>{flexRender(h.column.columnDef.header, h.getContext())}</TableHead>
                  ))}
                </TableRow>
              ))}
            </TableHeader>
            <TableBody>
              {tabla.getRowModel().rows.map((fila) => (
                <TableRow key={fila.id} className={cn(idsEnVivo.has(fila.original.id) && 'bg-sky-50/60 dark:bg-sky-950/40')}>
                  {fila.getVisibleCells().map((celda) => (
                    <TableCell key={celda.id}>{flexRender(celda.column.columnDef.cell, celda.getContext())}</TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {!bandeja.isLoading && filas.length === 0 && (
            <p className="px-4 py-10 text-center text-sm text-muted-foreground">No hay casos con estos filtros.</p>
          )}
          {bandeja.isLoading && <p className="px-4 py-10 text-center text-sm text-muted-foreground">Cargando…</p>}
        </div>
      )}

      <div className="flex items-center justify-end gap-2">
        <Button variant="outline" size="sm" disabled={pagina === 0} onClick={() => setPagina((p) => p - 1)}>
          Anterior
        </Button>
        <span className="text-sm text-muted-foreground">
          Página {pagina + 1} de {paginas}
        </span>
        <Button variant="outline" size="sm" disabled={pagina + 1 >= paginas} onClick={() => setPagina((p) => p + 1)}>
          Siguiente
        </Button>
      </div>
    </Pagina>
  );
}
