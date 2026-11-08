import type { EChartsCoreOption } from 'echarts/core';
import { useMemo } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Pagina } from '@/paginas/Pagina';
import { useMetricasCasos, useMetricasScoring, useMetricasTransacciones } from './api';
import { Grafico } from './Grafico';
import { Indicador } from './Indicador';
import { COLOR_DECISION, COLOR_SERIE, completarMinutos, milisegundos, numero, porcentaje, TINTA } from './series';
import type { MetricasCasos, MetricasTransacciones } from './tipos';

const hora = new Intl.DateTimeFormat('es-PE', { timeZone: 'America/Lima', hour: '2-digit', minute: '2-digit' });

function opcionVolumen(m: MetricasTransacciones): EChartsCoreOption {
  const serie = completarMinutos(m.porMinuto, m.desde, m.hasta);
  const barra = (nombre: string, clave: 'aprobar' | 'revisar' | 'rechazar', color: string) => ({
    name: nombre,
    type: 'bar',
    stack: 'decision',
    barCategoryGap: '20%',
    // Separador de 2 px del color de la superficie entre segmentos apilados.
    itemStyle: { color, borderColor: TINTA.superficie, borderWidth: 1 },
    emphasis: { focus: 'series' },
    data: serie.map((s) => s[clave]),
  });
  return {
    legend: { top: 0, left: 0, itemWidth: 10, itemHeight: 10, textStyle: { color: TINTA.secundaria } },
    xAxis: {
      type: 'category',
      data: serie.map((s) => hora.format(new Date(s.minuto))),
      axisLine: { lineStyle: { color: TINTA.eje } },
      axisTick: { show: false },
      axisLabel: { color: TINTA.apagada, hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: TINTA.rejilla } },
      axisLabel: { color: TINTA.apagada },
    },
    series: [
      barra('Aprobar', 'aprobar', COLOR_DECISION.APROBAR),
      barra('Revisar', 'revisar', COLOR_DECISION.REVISAR),
      barra('Rechazar', 'rechazar', COLOR_DECISION.RECHAZAR),
    ],
  };
}

function opcionAnalistas(m: MetricasCasos): EChartsCoreOption {
  const filas = [...m.abiertosPorAnalista].reverse();
  return {
    tooltip: { trigger: 'item' },
    grid: { left: 8, right: 32, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: TINTA.rejilla } },
      axisLabel: { color: TINTA.apagada } },
    yAxis: { type: 'category', data: filas.map((f) => f.analista ?? 'Sin asignar'),
      axisLine: { lineStyle: { color: TINTA.eje } }, axisTick: { show: false }, axisLabel: { color: TINTA.secundaria } },
    series: [{
      name: 'Casos abiertos',
      type: 'bar',
      barMaxWidth: 18,
      itemStyle: { color: COLOR_SERIE, borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: TINTA.secundaria },
      data: filas.map((f) => f.abiertos),
    }],
  };
}

function AvisoError({ servicio, mensaje }: { servicio: string; mensaje: string }) {
  return <p className="text-sm text-destructive">No se pudieron cargar las métricas de {servicio}: {mensaje}</p>;
}

/** Tablero de la última hora: se refresca cada 10 s con lo que entrega cada servicio (ADR 0017). */
export function PaginaDashboard() {
  const scoring = useMetricasScoring('1h');
  const transacciones = useMetricasTransacciones('1h');
  const casos = useMetricasCasos('1h');
  const volumen = useMemo(() => (transacciones.data ? opcionVolumen(transacciones.data) : null), [transacciones.data]);
  const analistas = useMemo(() => (casos.data ? opcionAnalistas(casos.data) : null), [casos.data]);
  const t = transacciones.data;
  const s = scoring.data;
  const c = casos.data;

  return (
    <Pagina titulo="Dashboard" descripcion="Última hora, actualizado cada 10 segundos. Detección según verdad.jsonl subida.">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Indicador nombre="Transacciones" valor={numero(t?.total)}
          detalle={t ? `${numero(t.porDecision.REVISAR)} revisar · ${numero(t.porDecision.RECHAZAR)} rechazar` : undefined} />
        <Indicador nombre="Respaldos" valor={numero(t?.respaldos)}
          detalle={t ? `${numero(t.tarjetaBloqueada)} con tarjeta bloqueada` : undefined} />
        <Indicador nombre="Detección" valor={porcentaje(s?.deteccion.deteccion)}
          detalle={s ? `${numero(s.deteccion.fraudesMarcados)} de ${numero(s.deteccion.fraudes)} fraudes` : undefined} />
        <Indicador nombre="Precisión" valor={porcentaje(s?.deteccion.precision)}
          detalle={s ? `falsos positivos ${porcentaje(s.deteccion.falsosPositivos)}` : undefined} />
        <Indicador nombre="Latencia p95" valor={milisegundos(s?.latenciaMs.p95)}
          detalle={s ? `p50 ${milisegundos(s.latenciaMs.p50)} · p99 ${milisegundos(s.latenciaMs.p99)}` : undefined} />
        <Indicador nombre="Casos abiertos" valor={numero(c?.abiertos)}
          detalle={c ? `${c.abiertosPorPrioridad.ALTA} ALTA · ${c.abiertosPorPrioridad.MEDIA} MEDIA` : undefined} />
      </div>
      {scoring.isError && <AvisoError servicio="scoring" mensaje={scoring.error.message} />}
      {casos.isError && <AvisoError servicio="casos" mensaje={casos.error.message} />}

      <div className="grid gap-6 xl:grid-cols-3">
        <Card className="gap-2 py-5 xl:col-span-2">
          <CardHeader className="px-5"><CardTitle className="text-base">Transacciones por minuto según decisión</CardTitle></CardHeader>
          <CardContent className="px-5">
            {transacciones.isError && <AvisoError servicio="transacciones" mensaje={transacciones.error.message} />}
            {volumen && <Grafico titulo="Transacciones por minuto" opcion={volumen} />}
          </CardContent>
        </Card>
        <Card className="gap-2 py-5">
          <CardHeader className="px-5"><CardTitle className="text-base">Casos abiertos por analista</CardTitle></CardHeader>
          <CardContent className="px-5">
            {analistas && c && c.abiertosPorAnalista.length > 0 && (
              <Grafico titulo="Casos abiertos por analista" opcion={analistas} alto={Math.max(160, 36 * c.abiertosPorAnalista.length)} />
            )}
            {c && c.abiertosPorAnalista.length === 0 && <p className="text-sm text-muted-foreground">No hay casos abiertos.</p>}
          </CardContent>
        </Card>
      </div>
    </Pagina>
  );
}
