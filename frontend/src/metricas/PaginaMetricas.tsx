import type { EChartsCoreOption } from 'echarts/core';
import { useMemo, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { formatearMonto } from '@/lib/formato';
import { Pagina } from '@/paginas/Pagina';
import { useMetricasCasos, useMetricasScoring, useMetricasTransacciones } from './api';
import { Grafico } from './Grafico';
import { Indicador } from './Indicador';
import { COLOR_DECISION, COLOR_SERIE, milisegundos, numero, porcentaje, TINTA } from './series';
import type { MetricasScoring, MetricasTransacciones, Ventana } from './tipos';

const VENTANAS: { valor: Ventana; etiqueta: string }[] = [
  { valor: '1h', etiqueta: 'Última hora' },
  { valor: '24h', etiqueta: '24 horas' },
  { valor: '7d', etiqueta: '7 días' },
];

function opcionEscenarios(m: MetricasScoring): EChartsCoreOption {
  const filas = [...m.porEscenario].reverse();
  return {
    tooltip: { trigger: 'item', valueFormatter: (v: number) => porcentaje(v) },
    grid: { left: 8, right: 56, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: TINTA.rejilla } },
      axisLabel: { color: TINTA.apagada, formatter: '{value} %' } },
    yAxis: { type: 'category', data: filas.map((f) => f.escenario), axisTick: { show: false },
      axisLine: { lineStyle: { color: TINTA.eje } }, axisLabel: { color: TINTA.secundaria } },
    series: [{
      name: 'Detección',
      type: 'bar',
      barMaxWidth: 18,
      itemStyle: { color: COLOR_SERIE, borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: TINTA.secundaria, formatter: (p: { value: number }) => porcentaje(p.value) },
      data: filas.map((f) => f.deteccion),
    }],
  };
}

function opcionDecisiones(m: MetricasTransacciones): EChartsCoreOption {
  const filas = [
    { nombre: 'Rechazar', valor: m.porDecision.RECHAZAR, color: COLOR_DECISION.RECHAZAR },
    { nombre: 'Revisar', valor: m.porDecision.REVISAR, color: COLOR_DECISION.REVISAR },
    { nombre: 'Aprobar', valor: m.porDecision.APROBAR, color: COLOR_DECISION.APROBAR },
  ];
  return {
    tooltip: { trigger: 'item' },
    grid: { left: 8, right: 64, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: TINTA.rejilla } }, axisLabel: { color: TINTA.apagada } },
    yAxis: { type: 'category', data: filas.map((f) => f.nombre), axisTick: { show: false },
      axisLine: { lineStyle: { color: TINTA.eje } }, axisLabel: { color: TINTA.secundaria } },
    series: [{
      name: 'Transacciones',
      type: 'bar',
      barMaxWidth: 18,
      label: { show: true, position: 'right', color: TINTA.secundaria },
      data: filas.map((f) => ({ value: f.valor, itemStyle: { color: f.color, borderRadius: [0, 4, 4, 0] } })),
    }],
  };
}

function Seccion({ titulo, children }: { titulo: string; children: React.ReactNode }) {
  return (
    <Card className="gap-2 py-5">
      <CardHeader className="px-5"><CardTitle className="text-base">{titulo}</CardTitle></CardHeader>
      <CardContent className="px-5">{children}</CardContent>
    </Card>
  );
}

/** Métricas de §9 con ventana elegible; cada bloque viene del servicio dueño de sus datos (ADR 0017). */
export function PaginaMetricas() {
  const [ventana, setVentana] = useState<Ventana>('24h');
  const scoring = useMetricasScoring(ventana);
  const transacciones = useMetricasTransacciones(ventana);
  const casos = useMetricasCasos(ventana);
  const s = scoring.data;
  const t = transacciones.data;
  const c = casos.data;
  const escenarios = useMemo(() => (s && s.porEscenario.length > 0 ? opcionEscenarios(s) : null), [s]);
  const decisiones = useMemo(() => (t ? opcionDecisiones(t) : null), [t]);

  return (
    <Pagina titulo="Métricas" descripcion="Detección contra verdad.jsonl (comando metricas del generador), latencia, decisiones y casos.">
      <div role="radiogroup" aria-label="Ventana" className="flex gap-2">
        {VENTANAS.map((v) => (
          <Button key={v.valor} size="sm" variant={ventana === v.valor ? 'default' : 'outline'} role="radio"
            aria-checked={ventana === v.valor} onClick={() => setVentana(v.valor)}>
            {v.etiqueta}
          </Button>
        ))}
      </div>

      {scoring.isError && <p className="text-sm text-destructive">Scoring: {scoring.error.message}</p>}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Indicador nombre="Detección" valor={porcentaje(s?.deteccion.deteccion)}
          detalle={s ? `${numero(s.deteccion.fraudesMarcados)} de ${numero(s.deteccion.fraudes)} fraudes marcados` : undefined} />
        <Indicador nombre="Precisión" valor={porcentaje(s?.deteccion.precision)}
          detalle={s ? `${numero(s.deteccion.fraudesMarcados)} de ${numero(s.deteccion.marcadas)} marcadas` : undefined} />
        <Indicador nombre="Falsos positivos" valor={porcentaje(s?.deteccion.falsosPositivos)}
          detalle={s ? `${numero(s.deteccion.legitimasMarcadas)} de ${numero(s.deteccion.legitimas)} legítimas` : undefined} />
        <Indicador nombre="Evaluaciones" valor={numero(s?.evaluaciones)}
          detalle={s ? `${s.transaccionesPorSegundo} por segundo · ${numero(s.evaluacionesTardias)} tardías` : undefined} />
        <Indicador nombre="Fraude evitado" valor={s ? formatearMonto(s.deteccion.montoFraudeEvitado) : '—'} detalle="fraudes rechazados" />
        <Indicador nombre="Fraude no detectado" valor={s ? formatearMonto(s.deteccion.montoFraudeNoDetectado) : '—'}
          detalle="fraudes aprobados o tardíos" />
        <Indicador nombre="Latencia p95" valor={milisegundos(s?.latenciaMs.p95)}
          detalle={s ? `p50 ${milisegundos(s.latenciaMs.p50)} · p99 ${milisegundos(s.latenciaMs.p99)} · objetivo < 50 ms` : undefined} />
        <Indicador nombre="Respaldos" valor={numero(t?.respaldos)}
          detalle={t ? `de ${numero(t.total)} transacciones` : undefined} />
      </div>

      <div className="grid gap-6 xl:grid-cols-2">
        <Seccion titulo="Detección por escenario">
          {escenarios && <Grafico titulo="Detección por escenario" opcion={escenarios} alto={Math.max(160, 44 * (s?.porEscenario.length ?? 0))} />}
          {s && s.porEscenario.length === 0 && (
            <p className="text-sm text-muted-foreground">Sin fraudes etiquetados en esta ventana. Sube la verdad con el comando metricas.</p>
          )}
          {s && s.porEscenario.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Escenario</TableHead>
                  <TableHead className="text-right">Fraudes</TableHead>
                  <TableHead className="text-right">Marcados</TableHead>
                  <TableHead className="text-right">Detección</TableHead>
                  <TableHead className="text-right">Evitado</TableHead>
                  <TableHead className="text-right">No detectado</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {s.porEscenario.map((e) => (
                  <TableRow key={e.escenario}>
                    <TableCell className="font-medium">{e.escenario}</TableCell>
                    <TableCell className="text-right tabular-nums">{numero(e.fraudes)}</TableCell>
                    <TableCell className="text-right tabular-nums">{numero(e.fraudesMarcados)}</TableCell>
                    <TableCell className="text-right tabular-nums">{porcentaje(e.deteccion)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatearMonto(e.montoFraudeEvitado)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatearMonto(e.montoFraudeNoDetectado)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <p className="mt-3 text-xs text-muted-foreground">
            Precisión y falsos positivos son solo globales: una transacción legítima no pertenece a ningún escenario (ADR 0017).
          </p>
        </Seccion>

        <Seccion titulo="Decisiones">
          {transacciones.isError && <p className="text-sm text-destructive">Transacciones: {transacciones.error.message}</p>}
          {decisiones && <Grafico titulo="Transacciones por decisión" opcion={decisiones} alto={170} />}
          {t && (
            <p className="mt-2 text-xs text-muted-foreground">
              Incluye {numero(t.tarjetaBloqueada)} rechazos por tarjeta bloqueada (sin evaluación) y {numero(t.pendientes)} pendientes.
            </p>
          )}
        </Seccion>

        <Seccion titulo="Casos">
          {casos.isError && <p className="text-sm text-destructive">Casos: {casos.error.message}</p>}
          {c && (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Indicador nombre="Abiertos" valor={numero(c.abiertos)}
                detalle={`${c.abiertosPorPrioridad.ALTA} ALTA · ${c.abiertosPorPrioridad.MEDIA} MEDIA`} />
              <Indicador nombre="Cerrados" valor={numero(c.cerrados)} detalle="en la ventana" />
              <Indicador nombre="Fraude confirmado" valor={numero(c.cerradosPorResolucion.FRAUDE_CONFIRMADO)}
                detalle={`${numero(c.cerradosPorResolucion.FALSO_POSITIVO)} falsos positivos`} />
              <Indicador nombre="Tiempo medio de resolución"
                valor={c.tiempoMedioResolucionMinutos === null ? '—' : `${c.tiempoMedioResolucionMinutos} min`} />
            </div>
          )}
        </Seccion>
      </div>
    </Pagina>
  );
}
