import { afterEach, describe, expect, it, vi } from 'vitest';
import { sesionDePrueba } from '@/pruebas/renderizar';
import { crearClienteApi, ErrorApi } from './cliente';

function responder(status: number, cuerpo: unknown, tipo = 'application/json') {
  return vi.fn().mockResolvedValue(new Response(cuerpo === null ? null : JSON.stringify(cuerpo), {
    status,
    headers: { 'Content-Type': tipo },
  }));
}

describe('cliente de la API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('envía el token de la sesión en cada solicitud', async () => {
    const fetchFalso = responder(200, { total: 0 });
    vi.stubGlobal('fetch', fetchFalso);
    const api = crearClienteApi(sesionDePrueba(['ANALISTA'], 'analista1'));

    const datos = await api.get<{ total: number }>('/api/v1/casos');

    expect(datos).toEqual({ total: 0 });
    const [url, init] = fetchFalso.mock.calls[0]!;
    expect(url).toBe('/api/v1/casos');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer token-analista1');
  });

  it('envía el cuerpo como JSON en POST', async () => {
    const fetchFalso = responder(201, { id: 'n1' });
    vi.stubGlobal('fetch', fetchFalso);
    const api = crearClienteApi(sesionDePrueba(['ANALISTA']));

    await api.post('/api/v1/casos/c1/notas', { texto: 'hola' });

    const [, init] = fetchFalso.mock.calls[0]!;
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"texto":"hola"}');
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
  });

  it('convierte un Problem Details en ErrorApi con su detalle', async () => {
    vi.stubGlobal('fetch', responder(409, { status: 409, title: 'Conflicto de estado',
      detail: 'No se puede tomar un caso en estado CERRADO' }, 'application/problem+json'));
    const api = crearClienteApi(sesionDePrueba(['ANALISTA']));

    const error = await api.post('/api/v1/casos/c1/tomar').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ErrorApi);
    expect((error as ErrorApi).status).toBe(409);
    expect((error as ErrorApi).message).toBe('No se puede tomar un caso en estado CERRADO');
  });

  it('una respuesta sin cuerpo devuelve undefined', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const api = crearClienteApi(sesionDePrueba(['ADMIN']));

    await expect(api.delete('/api/v1/listas-bloqueo/x')).resolves.toBeUndefined();
  });
});
