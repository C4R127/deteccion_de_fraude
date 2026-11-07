import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFalsa } from '@/pruebas/apiFalsa';
import { renderizar } from '@/pruebas/renderizar';
import { PaginaListasBloqueo } from './PaginaListasBloqueo';
import type { EntradaLista } from './tipos';

const TARJETA = '6f1c2d3e-0000-4000-8000-0000000000a1';

function entrada(cambios: Partial<EntradaLista> = {}): EntradaLista {
  return {
    id: 'e1',
    tipo: 'DISPOSITIVO',
    valor: 'dev_abc',
    motivo: 'caso abc cerrado como fraude confirmado',
    origen: 'CASO',
    vigenteHasta: null,
    creadaEn: '2026-11-07T16:00:00Z',
    creadaPor: 'caso:abc',
    ...cambios,
  };
}

describe('listas de bloqueo (§11, ADR 0014)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('valida el formulario antes de bloquear', async () => {
    const api = apiFalsa({ 'GET /api/v1/listas-bloqueo': [] });
    renderizar(<PaginaListasBloqueo />, { roles: ['SUPERVISOR'] });

    await userEvent.selectOptions(await screen.findByLabelText('Tipo'), 'TARJETA');
    await userEvent.type(screen.getByLabelText('Valor'), '4821');
    await userEvent.click(screen.getByRole('button', { name: 'Bloquear' }));

    expect(await screen.findByText('La tarjeta se identifica por su id (UUID), no por su número.')).toBeInTheDocument();
    expect(screen.getByText('Indica el motivo.')).toBeInTheDocument();
    expect(api.llamadas.some((l) => l.metodo === 'POST')).toBe(false);
  });

  it('bloquea con vigencia en hora de Lima', async () => {
    const api = apiFalsa({
      'GET /api/v1/listas-bloqueo': [],
      'POST /api/v1/listas-bloqueo': entrada({ id: 'e2', tipo: 'TARJETA', valor: TARJETA, origen: 'MANUAL' }),
    });
    renderizar(<PaginaListasBloqueo />, { roles: ['ADMIN'] });

    await userEvent.selectOptions(await screen.findByLabelText('Tipo'), 'TARJETA');
    await userEvent.type(screen.getByLabelText('Valor'), TARJETA);
    await userEvent.type(screen.getByLabelText('Motivo'), 'reporte del cliente');
    await userEvent.type(screen.getByLabelText('Vigente hasta (Lima, opcional)'), '2099-12-31T18:30');
    await userEvent.click(screen.getByRole('button', { name: 'Bloquear' }));

    expect(await screen.findByText('Bloqueo guardado.')).toBeInTheDocument();
    expect(api.llamadas.find((l) => l.metodo === 'POST')?.cuerpo).toEqual({
      tipo: 'TARJETA',
      valor: TARJETA,
      motivo: 'reporte del cliente',
      vigenteHasta: '2099-12-31T23:30:00.000Z',
    });
  });

  it('vencer una entrada vigente llama a DELETE; las vencidas no tienen el botón', async () => {
    const api = apiFalsa({
      'GET /api/v1/listas-bloqueo': [
        entrada(),
        entrada({ id: 'e9', valor: 'dev_viejo', vigenteHasta: '2020-01-01T00:00:00Z' }),
      ],
      'DELETE /api/v1/listas-bloqueo/e1': new Response(null, { status: 204 }),
    });
    renderizar(<PaginaListasBloqueo />, { roles: ['ADMIN'] });

    const vigente = (await screen.findByText('dev_abc')).closest('tr')!;
    const vencida = screen.getByText('dev_viejo').closest('tr')!;
    expect(within(vencida).getByText('Vencida')).toBeInTheDocument();
    expect(within(vencida).queryByRole('button', { name: 'Vencer' })).not.toBeInTheDocument();

    await userEvent.click(within(vigente).getByRole('button', { name: 'Vencer' }));

    expect(api.llamadas.some((l) => l.metodo === 'DELETE' && l.url === '/api/v1/listas-bloqueo/e1')).toBe(true);
  });
});
