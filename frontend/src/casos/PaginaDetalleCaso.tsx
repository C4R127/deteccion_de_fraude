import { ArrowLeft } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { puede } from '@/auth/permisos';
import { useSesion } from '@/auth/sesion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { ETIQUETA_ESTADO, ETIQUETA_RESOLUCION, formatearDia, formatearFecha, formatearMonto } from '@/lib/formato';
import { cn } from '@/lib/utils';
import { accionesDisponibles } from './acciones';
import { useAccionCaso, useAgregarNota, useCaso, useCliente, usePerfil, useTransaccionesCliente } from './api';
import { InsigniaEstado, InsigniaPrioridad } from './insignias';
import { MapaTransacciones } from './MapaTransacciones';
import type { CasoDetalle, Resolucion } from './tipos';

function Seccion({ titulo, children, className }: { titulo: string; children: ReactNode; className?: string }) {
  return (
    <Card className={cn('gap-4 py-5', className)}>
      <CardHeader className="px-5">
        <CardTitle className="text-base">{titulo}</CardTitle>
      </CardHeader>
      <CardContent className="px-5">{children}</CardContent>
    </Card>
  );
}

function Dato({ etiqueta, children }: { etiqueta: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{etiqueta}</dt>
      <dd className="mt-0.5 text-sm font-medium">{children}</dd>
    </div>
  );
}

/** Botones de transición según estado y rol (§8.1); la API valida de nuevo. */
function Acciones({ caso }: { caso: CasoDetalle }) {
  const sesion = useSesion();
  const accion = useAccionCaso(caso.id);
  const [analista, setAnalista] = useState('');
  const [resolucion, setResolucion] = useState<Resolucion | null>(null);
  const [comentario, setComentario] = useState('');
  const [aviso, setAviso] = useState<string | null>(null);
  const disponibles = accionesDisponibles(caso, sesion);
  if (disponibles.length === 0) {
    return null;
  }

  function cerrar(e: FormEvent) {
    e.preventDefault();
    if (!resolucion) {
      setAviso('Elige una resolución para cerrar el caso.');
      return;
    }
    setAviso(null);
    accion.mutate({ accion: 'cerrar', cuerpo: { resolucion, comentario: comentario.trim() || undefined } });
  }

  return (
    <Seccion titulo="Acciones">
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-3">
          {disponibles.includes('tomar') && (
            <Button disabled={accion.isPending} onClick={() => accion.mutate({ accion: 'tomar' })}>
              Tomar caso
            </Button>
          )}
          {disponibles.includes('investigar') && (
            <Button disabled={accion.isPending} onClick={() => accion.mutate({ accion: 'investigar' })}>
              Investigar
            </Button>
          )}
          {disponibles.includes('asignar') && (
            <form
              className="flex items-end gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                accion.mutate({ accion: 'asignar', cuerpo: { analistaId: analista.trim() } });
              }}
            >
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="asignar-analista">{caso.estado === 'NUEVO' ? 'Asignar a' : 'Reasignar a'}</Label>
                <Input id="asignar-analista" className="w-44" placeholder="usuario del analista" value={analista}
                  onChange={(e) => setAnalista(e.target.value)} />
              </div>
              <Button type="submit" variant="outline" disabled={accion.isPending || !analista.trim()}>
                Asignar
              </Button>
            </form>
          )}
        </div>
        {disponibles.includes('cerrar') && (
          <form className="flex flex-col gap-3" onSubmit={cerrar}>
            <fieldset className="flex flex-wrap gap-4">
              <legend className="mb-2 text-sm font-medium">Resolución</legend>
              {(['FRAUDE_CONFIRMADO', 'FALSO_POSITIVO'] as const).map((r) => (
                <label key={r} className="flex items-center gap-2 text-sm">
                  <input type="radio" name="resolucion" value={r} checked={resolucion === r}
                    onChange={() => setResolucion(r)} />
                  {ETIQUETA_RESOLUCION[r]}
                </label>
              ))}
            </fieldset>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="cierre-comentario">Comentario del cierre</Label>
              <Textarea id="cierre-comentario" rows={2} value={comentario} onChange={(e) => setComentario(e.target.value)} />
            </div>
            <div>
              <Button type="submit" variant="destructive" disabled={accion.isPending}>
                Cerrar caso
              </Button>
            </div>
          </form>
        )}
        {aviso && <p className="text-sm text-destructive">{aviso}</p>}
        {accion.isError && <p className="text-sm text-destructive">{accion.error.message}</p>}
      </div>
    </Seccion>
  );
}

function Notas({ caso }: { caso: CasoDetalle }) {
  const { roles } = useSesion();
  const agregar = useAgregarNota(caso.id);
  const [texto, setTexto] = useState('');
  return (
    <Seccion titulo="Notas">
      <div className="flex flex-col gap-4">
        {caso.notas.length === 0 && <p className="text-sm text-muted-foreground">Sin notas.</p>}
        <ul className="flex flex-col gap-3">
          {caso.notas.map((n) => (
            <li key={n.id} className="rounded-md bg-muted/50 px-3 py-2">
              <p className="text-sm whitespace-pre-wrap">{n.texto}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                {n.autorId} · {formatearFecha(n.creadaEn)}
              </p>
            </li>
          ))}
        </ul>
        {puede(roles, 'agregarNotas') && (
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              agregar.mutate(texto.trim(), { onSuccess: () => setTexto('') });
            }}
          >
            <Label htmlFor="nota-texto" className="sr-only">Nueva nota</Label>
            <Textarea id="nota-texto" rows={2} placeholder="Escribe una nota" value={texto}
              onChange={(e) => setTexto(e.target.value)} />
            <div>
              <Button type="submit" variant="outline" size="sm" disabled={!texto.trim() || agregar.isPending}>
                Agregar nota
              </Button>
            </div>
            {agregar.isError && <p className="text-sm text-destructive">{agregar.error.message}</p>}
          </form>
        )}
      </div>
    </Seccion>
  );
}

function ClienteYPerfil({ clienteId }: { clienteId: string }) {
  const cliente = useCliente(clienteId);
  const perfil = usePerfil(clienteId);
  return (
    <Seccion titulo="Cliente">
      <dl className="grid grid-cols-2 gap-3">
        <Dato etiqueta="Nombre">{cliente.data?.nombre ?? '…'}</Dato>
        <Dato etiqueta="Segmento">{cliente.data?.segmento ?? '…'}</Dato>
        <Dato etiqueta="Ciudad">{cliente.data?.ciudadResidencia ?? '…'}</Dato>
        <Dato etiqueta="Cliente desde">{cliente.data ? formatearDia(cliente.data.fechaAlta) : '…'}</Dato>
      </dl>
      <h3 className="mt-5 mb-2 text-sm font-medium">Perfil (30 días)</h3>
      {perfil.isLoading && <p className="text-sm text-muted-foreground">Cargando…</p>}
      {perfil.data === null && <p className="text-sm text-muted-foreground">Sin perfil todavía.</p>}
      {perfil.data && (
        <dl className="grid grid-cols-2 gap-3">
          <Dato etiqueta="Transacciones">{perfil.data.n30d}</Dato>
          <Dato etiqueta="Gasto promedio">{formatearMonto(perfil.data.promedio30d)}</Dato>
          <Dato etiqueta="Países">{perfil.data.paises.join(', ') || '—'}</Dato>
          <Dato etiqueta="Rubros (MCC)">{perfil.data.mccs.join(', ') || '—'}</Dato>
          <Dato etiqueta="Horas habituales (Lima)">
            {perfil.data.horas.map((n, h) => (n > 0 ? h : null)).filter((h) => h !== null).join(', ') || '—'}
          </Dato>
          <Dato etiqueta="Dispositivos conocidos">{perfil.data.dispositivos.length}</Dato>
        </dl>
      )}
    </Seccion>
  );
}

function Transacciones({ caso }: { caso: CasoDetalle }) {
  const transacciones = useTransaccionesCliente(caso.clienteId);
  const lista = transacciones.data ?? [];
  return (
    <Seccion titulo="Últimas 20 transacciones del cliente" className="lg:col-span-2">
      <div className="flex flex-col gap-4">
        <MapaTransacciones transacciones={lista} transaccionCaso={caso.transaccionId} />
        {transacciones.isLoading && <p className="text-sm text-muted-foreground">Cargando…</p>}
        {lista.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Fecha (Lima)</TableHead>
                <TableHead>Comercio</TableHead>
                <TableHead>Canal</TableHead>
                <TableHead>Tarjeta</TableHead>
                <TableHead className="text-right">Monto</TableHead>
                <TableHead>Decisión</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {lista.map((t) => (
                <TableRow key={t.id} className={cn(t.id === caso.transaccionId && 'bg-red-50 dark:bg-red-950/40')}>
                  <TableCell className="whitespace-nowrap">{formatearFecha(t.fecha)}</TableCell>
                  <TableCell>
                    {t.comercioNombre}
                    <span className="block text-xs text-muted-foreground">MCC {t.mcc} · {t.ciudad}</span>
                  </TableCell>
                  <TableCell>{t.canal}</TableCell>
                  <TableCell className="tabular-nums">•••• {t.ultimos4}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatearMonto(t.monto)}</TableCell>
                  <TableCell>
                    {t.decision ?? '—'}
                    {t.respaldo && <Badge variant="outline" className="ml-2">respaldo</Badge>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>
    </Seccion>
  );
}

/** Detalle del caso (§8.3): reglas, cliente y perfil, transacciones con mapa, notas e historial. */
export function PaginaDetalleCaso() {
  const { id = '' } = useParams();
  const consulta = useCaso(id);

  if (consulta.isLoading) {
    return <p className="text-sm text-muted-foreground">Cargando caso…</p>;
  }
  if (consulta.isError || !consulta.data) {
    return <p className="text-sm text-destructive">No se pudo cargar el caso: {consulta.error?.message}</p>;
  }
  const caso = consulta.data;

  return (
    <section className="flex flex-col gap-6">
      <Link to="/casos" className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" aria-hidden />
        Bandeja
      </Link>
      <header className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Caso {formatearMonto(caso.monto)}</h1>
        <InsigniaPrioridad prioridad={caso.prioridad} />
        <InsigniaEstado estado={caso.estado} />
        {caso.resolucion && <Badge variant="secondary">{ETIQUETA_RESOLUCION[caso.resolucion]}</Badge>}
      </header>
      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-5">
        <Dato etiqueta="Score">{caso.score}</Dato>
        <Dato etiqueta="Decisión">{caso.decision}</Dato>
        <Dato etiqueta="Analista">{caso.analistaId ?? '—'}</Dato>
        <Dato etiqueta="Creado (Lima)">{formatearFecha(caso.creadoEn)}</Dato>
        <Dato etiqueta="Cerrado (Lima)">{formatearFecha(caso.cerradoEn)}</Dato>
      </dl>

      <Acciones caso={caso} />

      <div className="grid gap-6 lg:grid-cols-2">
        <Seccion titulo="Reglas disparadas">
          <ul className="flex flex-col gap-2">
            {caso.reglasDisparadas.map((r) => (
              <li key={r.codigo} className="flex items-start gap-3">
                <Badge variant="outline" className="tabular-nums">{r.codigo}</Badge>
                <span className="flex-1 text-sm">{r.detalle}</span>
                <span className="text-sm font-medium tabular-nums">+{r.puntos}</span>
              </li>
            ))}
          </ul>
        </Seccion>
        <ClienteYPerfil clienteId={caso.clienteId} />
        <Transacciones caso={caso} />
        <Notas caso={caso} />
        <Seccion titulo="Historial">
          <ol className="flex flex-col gap-3">
            {caso.historial.map((h, i) => (
              <li key={i} className="border-l-2 pl-3">
                <p className="text-sm">
                  {h.estadoAnterior ? `${ETIQUETA_ESTADO[h.estadoAnterior]} → ` : ''}
                  <span className="font-medium">{ETIQUETA_ESTADO[h.estadoNuevo]}</span>
                </p>
                <p className="text-xs text-muted-foreground">
                  <span>{h.usuarioId}</span> · {formatearFecha(h.fecha)}
                </p>
                {h.comentario && <p className="mt-1 text-sm">{h.comentario}</p>}
              </li>
            ))}
          </ol>
        </Seccion>
      </div>
    </section>
  );
}
