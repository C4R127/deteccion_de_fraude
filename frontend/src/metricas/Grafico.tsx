import { BarChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import type { EChartsCoreOption } from 'echarts/core';
import { useEffect, useRef } from 'react';
import { TINTA } from './series';

// Solo lo que se usa: barras, ejes, leyenda y tooltip, con render SVG (nítido y liviano).
echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer]);

/** Opciones comunes: tinta recesiva, rejilla de una línea, tooltip al pasar el cursor. */
const BASE: EChartsCoreOption = {
  textStyle: { fontFamily: 'inherit', color: TINTA.secundaria },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
  grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
};

/**
 * Contenedor de ECharts. {@code titulo} nombra el gráfico para lectores de pantalla; el
 * componente que lo usa debe ofrecer también la tabla o los números (ver la página).
 */
export function Grafico({ titulo, opcion, alto = 280 }: { titulo: string; opcion: EChartsCoreOption; alto?: number }) {
  const contenedor = useRef<HTMLDivElement>(null);
  const grafico = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    const div = contenedor.current!;
    grafico.current = echarts.init(div, undefined, { renderer: 'svg' });
    const observador = new ResizeObserver(() => grafico.current?.resize());
    observador.observe(div);
    return () => {
      observador.disconnect();
      grafico.current?.dispose();
      grafico.current = null;
    };
  }, []);

  useEffect(() => {
    grafico.current?.setOption({ ...BASE, ...opcion }, { notMerge: true });
  }, [opcion]);

  return <div ref={contenedor} role="img" aria-label={titulo} style={{ height: alto }} className="w-full" />;
}
