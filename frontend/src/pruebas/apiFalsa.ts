import { vi } from 'vitest';

type Manejador = (init: RequestInit, url: string) => unknown;

/**
 * Reemplaza {@code fetch} por respuestas según "MÉTODO ruta" (sin query string). Un manejador
 * que devuelve {@code Response} se usa tal cual; cualquier otro valor se envía como JSON 200.
 */
export function apiFalsa(rutas: Record<string, Manejador | unknown>) {
  const llamadas: { metodo: string; url: string; cuerpo?: unknown }[] = [];
  const fetchFalso = vi.fn(async (url: string, init: RequestInit = {}) => {
    const metodo = init.method ?? 'GET';
    const ruta = url.split('?')[0];
    llamadas.push({ metodo, url, cuerpo: init.body ? JSON.parse(String(init.body)) : undefined });
    const clave = `${metodo} ${ruta}`;
    if (!(clave in rutas)) {
      return new Response(JSON.stringify({ status: 404, detail: `sin respuesta falsa para ${clave}` }), {
        status: 404,
        headers: { 'Content-Type': 'application/problem+json' },
      });
    }
    const valor = rutas[clave];
    const resultado = typeof valor === 'function' ? (valor as Manejador)(init, url) : valor;
    if (resultado instanceof Response) {
      return resultado;
    }
    return new Response(JSON.stringify(resultado), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchFalso);
  return { llamadas };
}
