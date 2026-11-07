import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFalsa } from '@/pruebas/apiFalsa';
import { renderizar } from '@/pruebas/renderizar';
import { PaginaReglas } from './PaginaReglas';
import type { VersionRegla } from './tipos';

function regla(cambios: Partial<VersionRegla> = {}): VersionRegla {
  return {
    codigo: 'R01',
    nombre: 'Monto inusual',
    descripcion: 'Monto mayor que factor × promedio de 30 días',
    parametros: { factor: 5, minimoTransacciones: 10 },
    puntos: 40,
    activa: true,
    version: 1,
    vigente: true,
    creadaEn: null,
    creadaPor: null,
    ...cambios,
  };
}

const R06 = regla({ codigo: 'R06', nombre: 'Rubro de riesgo', parametros: { mccs: ['7995', '6051', '4829'] }, puntos: 15 });
const R07 = regla({ codigo: 'R07', nombre: 'Horario inusual', puntos: 10,
  parametros: { horaInicio: 2, horaFin: 4, zona: 'America/Lima', minimoTransacciones: 10 } });

async function editar(codigo: string) {
  const fila = (await screen.findByText(codigo)).closest('tr')!;
  await userEvent.click(within(fila).getByRole('button', { name: 'Editar' }));
}

async function reemplazar(etiqueta: string, valor: string) {
  const campo = screen.getByLabelText(etiqueta);
  await userEvent.clear(campo);
  if (valor) await userEvent.type(campo, valor);
}

describe('editor de reglas (§7.2, ADR 0014)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('el formulario valida parámetros numéricos positivos', async () => {
    const api = apiFalsa({ 'GET /api/v1/reglas': [regla()] });
    renderizar(<PaginaReglas />, { roles: ['SUPERVISOR'] });
    await editar('R01');

    await reemplazar('factor', '0');
    await reemplazar('Puntos', '-5');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));

    expect(await screen.findByText('Debe ser un número mayor que 0.')).toBeInTheDocument();
    expect(screen.getByText('Entero entre 0 y 100.')).toBeInTheDocument();
    expect(api.llamadas.some((l) => l.metodo === 'PUT')).toBe(false);

    await reemplazar('factor', 'abc');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));
    expect(await screen.findByText('Debe ser un número mayor que 0.')).toBeInTheDocument();
    expect(api.llamadas.some((l) => l.metodo === 'PUT')).toBe(false);
  });

  it('guardar envía los parámetros con su tipo y muestra la nueva versión', async () => {
    const api = apiFalsa({
      'GET /api/v1/reglas': [regla()],
      'PUT /api/v1/reglas/R01': regla({ parametros: { factor: 6.5, minimoTransacciones: 10 }, version: 2 }),
    });
    renderizar(<PaginaReglas />, { roles: ['ADMIN'] });
    await editar('R01');

    await reemplazar('factor', '6.5');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));

    expect(await screen.findByText('Versión 2 de R01 guardada.')).toBeInTheDocument();
    const put = api.llamadas.find((l) => l.metodo === 'PUT');
    expect(put?.url).toBe('/api/v1/reglas/R01');
    expect(put?.cuerpo).toEqual({
      nombre: 'Monto inusual',
      descripcion: 'Monto mayor que factor × promedio de 30 días',
      parametros: { factor: 6.5, minimoTransacciones: 10 },
      puntos: 40,
      activa: true,
    });
  });

  it('las listas se editan separadas por comas y las horas admiten 0', async () => {
    const api = apiFalsa({
      'GET /api/v1/reglas': [R06, R07],
      'PUT /api/v1/reglas/R06': { ...R06, version: 2 },
      'PUT /api/v1/reglas/R07': { ...R07, version: 2 },
    });
    renderizar(<PaginaReglas />, { roles: ['ADMIN'] });

    await editar('R06');
    await reemplazar('mccs', '7995, 6051 ,, 4829, 6211');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));
    await screen.findByText('Versión 2 de R06 guardada.');
    expect(api.llamadas.find((l) => l.url === '/api/v1/reglas/R06')?.cuerpo)
      .toMatchObject({ parametros: { mccs: ['7995', '6051', '4829', '6211'] } });

    await editar('R07');
    await reemplazar('horaInicio', '0');
    await reemplazar('horaFin', '24');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));
    expect(await screen.findByText('Hora entera entre 0 y 23.')).toBeInTheDocument();
    await reemplazar('horaFin', '4');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));
    await screen.findByText('Versión 2 de R07 guardada.');
    expect(api.llamadas.find((l) => l.url === '/api/v1/reglas/R07')?.cuerpo)
      .toMatchObject({ parametros: { horaInicio: 0, horaFin: 4, zona: 'America/Lima', minimoTransacciones: 10 } });
  });

  it('muestra el error de la API sin perder lo escrito', async () => {
    apiFalsa({
      'GET /api/v1/reglas': [regla()],
      'PUT /api/v1/reglas/R01': new Response(JSON.stringify({ status: 422,
        detail: 'Los parámetros de R01 deben ser exactamente [factor, minimoTransacciones]' }),
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } }),
    });
    renderizar(<PaginaReglas />, { roles: ['ADMIN'] });
    await editar('R01');

    await userEvent.click(screen.getByRole('button', { name: 'Guardar versión nueva' }));

    expect(await screen.findByText('Los parámetros de R01 deben ser exactamente [factor, minimoTransacciones]'))
      .toBeInTheDocument();
    expect(screen.getByLabelText('factor')).toHaveValue('5');
  });
});
