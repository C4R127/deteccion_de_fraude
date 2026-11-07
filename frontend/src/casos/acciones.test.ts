import { describe, expect, it } from 'vitest';
import { sesionDePrueba } from '@/pruebas/renderizar';
import { accionesDisponibles } from './acciones';
import { casoDePrueba } from './datosPrueba';

const analista1 = sesionDePrueba(['ANALISTA'], 'analista1');
const analista2 = sesionDePrueba(['ANALISTA'], 'analista2');
const supervisor = sesionDePrueba(['SUPERVISOR'], 'supervisor');
const auditor = sesionDePrueba(['AUDITOR'], 'auditor');

describe('acciones disponibles según estado y rol (§8.1)', () => {
  it('NUEVO: el analista toma; el supervisor toma o asigna', () => {
    const caso = casoDePrueba({ estado: 'NUEVO' });
    expect(accionesDisponibles(caso, analista1)).toEqual(['tomar']);
    expect(accionesDisponibles(caso, supervisor)).toEqual(['tomar', 'asignar']);
  });

  it('ASIGNADO: solo el asignado investiga; el supervisor reasigna', () => {
    const caso = casoDePrueba({ estado: 'ASIGNADO', analistaId: 'analista1' });
    expect(accionesDisponibles(caso, analista1)).toEqual(['investigar']);
    expect(accionesDisponibles(caso, analista2)).toEqual([]);
    expect(accionesDisponibles(caso, supervisor)).toEqual(['asignar']);
  });

  it('EN_INVESTIGACION: cierran el asignado y el supervisor', () => {
    const caso = casoDePrueba({ estado: 'EN_INVESTIGACION', analistaId: 'analista1' });
    expect(accionesDisponibles(caso, analista1)).toEqual(['cerrar']);
    expect(accionesDisponibles(caso, analista2)).toEqual([]);
    expect(accionesDisponibles(caso, supervisor)).toEqual(['cerrar']);
  });

  it('CERRADO no tiene acciones', () => {
    const caso = casoDePrueba({ estado: 'CERRADO', analistaId: 'analista1', resolucion: 'FALSO_POSITIVO' });
    expect(accionesDisponibles(caso, analista1)).toEqual([]);
    expect(accionesDisponibles(caso, supervisor)).toEqual([]);
  });

  it('el auditor nunca tiene acciones', () => {
    for (const estado of ['NUEVO', 'ASIGNADO', 'EN_INVESTIGACION', 'CERRADO'] as const) {
      expect(accionesDisponibles(casoDePrueba({ estado, analistaId: 'auditor' }), auditor)).toEqual([]);
    }
  });
});
