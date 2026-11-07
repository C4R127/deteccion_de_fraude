import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type Resolver } from 'react-hook-form';
import { Campo, CLASE_SELECT } from '@/components/Campo';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { formatearFecha } from '@/lib/formato';
import { Pagina } from '@/paginas/Pagina';
import { useBloquear, useListas, useVencer } from './api';
import { esquemaBloqueo, type BloqueoNuevo, type FormularioBloqueo } from './esquemas';
import type { EntradaLista } from './tipos';

function vigente(e: EntradaLista): boolean {
  return e.vigenteHasta === null || new Date(e.vigenteHasta) > new Date();
}

function FormularioNuevo() {
  const bloquear = useBloquear();
  const [aviso, setAviso] = useState<string | null>(null);
  const { register, handleSubmit, reset, formState: { errors } } = useForm<FormularioBloqueo, unknown, BloqueoNuevo>({
    defaultValues: { tipo: 'DISPOSITIVO', valor: '', motivo: '', vigenteHasta: '' },
    resolver: zodResolver(esquemaBloqueo) as unknown as Resolver<FormularioBloqueo, unknown, BloqueoNuevo>,
  });

  return (
    <Card className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-base">Bloquear</CardTitle>
      </CardHeader>
      <CardContent className="px-5">
        <form
          noValidate
          className="grid gap-4 md:grid-cols-[10rem_1fr_1fr_14rem_auto] md:items-start"
          onSubmit={handleSubmit((datos) => {
            setAviso(null);
            bloquear.mutate(datos, {
              onSuccess: () => {
                setAviso('Bloqueo guardado.');
                reset();
              },
            });
          })}
        >
          <Campo id="bloqueo-tipo" etiqueta="Tipo" error={errors.tipo?.message}>
            <select id="bloqueo-tipo" className={CLASE_SELECT} {...register('tipo')}>
              <option value="TARJETA">Tarjeta</option>
              <option value="DISPOSITIVO">Dispositivo</option>
              <option value="COMERCIO">Comercio</option>
            </select>
          </Campo>
          <Campo id="bloqueo-valor" etiqueta="Valor" error={errors.valor?.message}
            ayuda="Tarjeta: id (UUID). Dispositivo: huella. Comercio: id.">
            <Input id="bloqueo-valor" {...register('valor')} />
          </Campo>
          <Campo id="bloqueo-motivo" etiqueta="Motivo" error={errors.motivo?.message}>
            <Input id="bloqueo-motivo" {...register('motivo')} />
          </Campo>
          <Campo id="bloqueo-hasta" etiqueta="Vigente hasta (Lima, opcional)" error={errors.vigenteHasta?.message}>
            <Input id="bloqueo-hasta" type="datetime-local" {...register('vigenteHasta')} />
          </Campo>
          <Button type="submit" className="md:mt-[1.375rem]" disabled={bloquear.isPending}>Bloquear</Button>
        </form>
        {aviso && <p role="status" className="mt-3 text-sm font-medium text-emerald-700 dark:text-emerald-400">{aviso}</p>}
        {bloquear.isError && <p className="mt-3 text-sm text-destructive">{bloquear.error.message}</p>}
      </CardContent>
    </Card>
  );
}

/** Listas de bloqueo (§5.2, ADR 0014): alta manual y vencimiento; las filas no se borran. */
export function PaginaListasBloqueo() {
  const listas = useListas();
  const vencer = useVencer();

  return (
    <Pagina titulo="Listas de bloqueo"
      descripcion="Una coincidencia vigente rechaza la transacción con la regla B01. Vencer deja la entrada como evidencia.">
      <FormularioNuevo />
      {vencer.isError && <p className="text-sm text-destructive">{vencer.error.message}</p>}
      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Tipo</TableHead>
              <TableHead>Valor</TableHead>
              <TableHead>Motivo</TableHead>
              <TableHead>Origen</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead>Creada (Lima)</TableHead>
              <TableHead>Por</TableHead>
              <TableHead><span className="sr-only">Acciones</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(listas.data ?? []).map((e) => (
              <TableRow key={e.id}>
                <TableCell>{e.tipo}</TableCell>
                <TableCell className="max-w-56 truncate font-mono text-xs" title={e.valor}>{e.valor}</TableCell>
                <TableCell className="max-w-64 text-sm">{e.motivo}</TableCell>
                <TableCell>{e.origen}</TableCell>
                <TableCell className="whitespace-nowrap">
                  {vigente(e) ? (
                    <Badge variant="secondary">{e.vigenteHasta ? `Hasta ${formatearFecha(e.vigenteHasta)}` : 'Vigente'}</Badge>
                  ) : (
                    <Badge variant="outline">Vencida</Badge>
                  )}
                </TableCell>
                <TableCell className="whitespace-nowrap">{formatearFecha(e.creadaEn)}</TableCell>
                <TableCell className="text-sm">{e.creadaPor}</TableCell>
                <TableCell className="text-right">
                  {vigente(e) && (
                    <Button variant="outline" size="sm" disabled={vencer.isPending} onClick={() => vencer.mutate(e.id)}>
                      Vencer
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {listas.isLoading && <p className="px-4 py-8 text-center text-sm text-muted-foreground">Cargando…</p>}
        {listas.data?.length === 0 && (
          <p className="px-4 py-8 text-center text-sm text-muted-foreground">No hay entradas en las listas de bloqueo.</p>
        )}
      </div>
    </Pagina>
  );
}
