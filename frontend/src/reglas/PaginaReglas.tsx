import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type Resolver } from 'react-hook-form';
import { Campo } from '@/components/Campo';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { formatearFecha } from '@/lib/formato';
import { Pagina } from '@/paginas/Pagina';
import { useEditarRegla, useReglas, useVersiones } from './api';
import { esquemaRegla, tipoParametro, valoresIniciales, type FormularioRegla, type ReglaEditada } from './esquemas';
import type { ValorParametro, VersionRegla } from './tipos';

const AYUDA: Record<string, string> = {
  numero: 'Número mayor que 0',
  hora: 'Hora de 0 a 23',
  lista: 'Valores separados por comas',
  texto: 'Texto',
};

function mostrarParametro(valor: ValorParametro): string {
  return Array.isArray(valor) ? valor.join(', ') : String(valor);
}

/** Formulario de una regla; guardar crea la versión siguiente (ADR 0014). */
function EditorRegla({ regla, alGuardar, alCancelar }: {
  regla: VersionRegla;
  alGuardar: (guardada: VersionRegla) => void;
  alCancelar: () => void;
}) {
  const editar = useEditarRegla(regla.codigo);
  const { register, handleSubmit, formState: { errors } } = useForm<FormularioRegla, unknown, ReglaEditada>({
    defaultValues: valoresIniciales(regla),
    resolver: zodResolver(esquemaRegla(regla)) as unknown as Resolver<FormularioRegla, unknown, ReglaEditada>,
  });

  return (
    <Card className="gap-4 py-5">
      <CardHeader className="px-5">
        <CardTitle className="text-base">
          Editar {regla.codigo} <span className="font-normal text-muted-foreground">(versión vigente {regla.version})</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="px-5">
        <form
          className="flex flex-col gap-4"
          noValidate
          onSubmit={handleSubmit((datos) => editar.mutate(datos, { onSuccess: alGuardar }))}
        >
          <div className="grid gap-4 md:grid-cols-[1fr_8rem_auto]">
            <Campo id="regla-nombre" etiqueta="Nombre" error={errors.nombre?.message}>
              <Input id="regla-nombre" {...register('nombre')} />
            </Campo>
            <Campo id="regla-puntos" etiqueta="Puntos" error={errors.puntos?.message}>
              <Input id="regla-puntos" inputMode="numeric" {...register('puntos')} />
            </Campo>
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input type="checkbox" {...register('activa')} />
              Activa
            </label>
          </div>
          <Campo id="regla-descripcion" etiqueta="Descripción" error={errors.descripcion?.message}>
            <Textarea id="regla-descripcion" rows={2} {...register('descripcion')} />
          </Campo>
          {Object.keys(regla.parametros).length > 0 && (
            <fieldset className="grid gap-4 md:grid-cols-3">
              <legend className="mb-2 text-sm font-medium">Parámetros</legend>
              {Object.entries(regla.parametros).map(([clave, valor]) => {
                const id = `parametro-${clave}`;
                return (
                  <Campo key={clave} id={id} etiqueta={clave} ayuda={AYUDA[tipoParametro(clave, valor)]}
                    error={errors.parametros?.[clave]?.message}>
                    <Input id={id} {...register(`parametros.${clave}`)} />
                  </Campo>
                );
              })}
            </fieldset>
          )}
          {editar.isError && <p className="text-sm text-destructive">{editar.error.message}</p>}
          <div className="flex gap-2">
            <Button type="submit" disabled={editar.isPending}>Guardar versión nueva</Button>
            <Button type="button" variant="ghost" onClick={alCancelar}>Cancelar</Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function Versiones({ codigo }: { codigo: string }) {
  const versiones = useVersiones(codigo);
  if (versiones.isLoading) return <p className="text-sm text-muted-foreground">Cargando versiones…</p>;
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Versión</TableHead>
          <TableHead>Puntos</TableHead>
          <TableHead>Parámetros</TableHead>
          <TableHead>Creada (Lima)</TableHead>
          <TableHead>Por</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {(versiones.data ?? []).map((v) => (
          <TableRow key={v.version}>
            <TableCell>
              v{v.version} {v.vigente && <Badge variant="secondary">vigente</Badge>}
            </TableCell>
            <TableCell className="tabular-nums">{v.puntos}</TableCell>
            <TableCell className="max-w-96 text-xs whitespace-normal break-words">
              {Object.entries(v.parametros).map(([k, val]) => `${k}=${mostrarParametro(val)}`).join(' · ')}
            </TableCell>
            <TableCell className="whitespace-nowrap">{formatearFecha(v.creadaEn)}</TableCell>
            <TableCell>{v.creadaPor}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** Reglas R01–R07 (§7.2): parámetros y puntos editables, cada cambio crea una versión. */
export function PaginaReglas() {
  const reglas = useReglas();
  const [editando, setEditando] = useState<string | null>(null);
  const [historial, setHistorial] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const enEdicion = reglas.data?.find((r) => r.codigo === editando) ?? null;

  return (
    <Pagina titulo="Reglas" descripcion="Cada cambio crea una versión nueva; las evaluaciones guardan qué versión usaron.">
      {aviso && <p role="status" className="text-sm font-medium text-emerald-700 dark:text-emerald-400">{aviso}</p>}
      {reglas.isError && <p className="text-sm text-destructive">No se pudieron cargar las reglas: {reglas.error.message}</p>}
      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Código</TableHead>
              <TableHead>Nombre</TableHead>
              <TableHead>Parámetros</TableHead>
              <TableHead>Puntos</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead>Versión</TableHead>
              <TableHead><span className="sr-only">Acciones</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(reglas.data ?? []).map((r) => (
              <TableRow key={r.codigo}>
                <TableCell className="font-medium">{r.codigo}</TableCell>
                <TableCell>{r.nombre}</TableCell>
                <TableCell className="max-w-72 text-xs whitespace-normal break-words text-muted-foreground">
                  {Object.entries(r.parametros).map(([k, v]) => `${k}=${mostrarParametro(v)}`).join(' · ') || '—'}
                </TableCell>
                <TableCell className="tabular-nums">{r.puntos}</TableCell>
                <TableCell>{r.activa ? <Badge variant="secondary">Activa</Badge> : <Badge variant="outline">Inactiva</Badge>}</TableCell>
                <TableCell className="tabular-nums">v{r.version}</TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => setHistorial(historial === r.codigo ? null : r.codigo)}>
                    Versiones
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => { setEditando(r.codigo); setAviso(null); }}>
                    Editar
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {reglas.isLoading && <p className="px-4 py-8 text-center text-sm text-muted-foreground">Cargando…</p>}
      </div>
      {enEdicion && (
        <EditorRegla
          key={`${enEdicion.codigo}-${enEdicion.version}`}
          regla={enEdicion}
          alCancelar={() => setEditando(null)}
          alGuardar={(guardada) => {
            setAviso(`Versión ${guardada.version} de ${guardada.codigo} guardada.`);
            setEditando(null);
          }}
        />
      )}
      {historial && (
        <section className="flex flex-col gap-2">
          <h2 className="text-base font-semibold">Versiones de {historial}</h2>
          <div className="rounded-lg border"><Versiones codigo={historial} /></div>
        </section>
      )}
    </Pagina>
  );
}
