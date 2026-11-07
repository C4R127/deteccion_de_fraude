import { describe, expect, it } from 'vitest';
import { puede, rolesDeLaApp } from './permisos';

describe('permisos (§10)', () => {
  it('el auditor solo lee', () => {
    const auditor = ['AUDITOR'] as const;
    expect(puede([...auditor], 'ver')).toBe(true);
    expect(puede([...auditor], 'gestionarReglas')).toBe(false);
    expect(puede([...auditor], 'gestionarListas')).toBe(false);
    expect(puede([...auditor], 'asignarCasos')).toBe(false);
    expect(puede([...auditor], 'trabajarCasos')).toBe(false);
    expect(puede([...auditor], 'agregarNotas')).toBe(false);
  });

  it('el analista trabaja casos pero no asigna ni gestiona reglas', () => {
    expect(puede(['ANALISTA'], 'trabajarCasos')).toBe(true);
    expect(puede(['ANALISTA'], 'agregarNotas')).toBe(true);
    expect(puede(['ANALISTA'], 'asignarCasos')).toBe(false);
    expect(puede(['ANALISTA'], 'gestionarReglas')).toBe(false);
  });

  it('supervisor y admin pueden todo lo de casos, reglas y listas', () => {
    for (const rol of ['SUPERVISOR', 'ADMIN'] as const) {
      for (const permiso of ['ver', 'gestionarReglas', 'gestionarListas', 'asignarCasos', 'trabajarCasos',
        'agregarNotas'] as const) {
        expect(puede([rol], permiso)).toBe(true);
      }
    }
  });

  it('sin roles no puede nada', () => {
    expect(puede([], 'ver')).toBe(false);
  });

  it('filtra los roles del token que no son de la aplicación', () => {
    expect(rolesDeLaApp(['offline_access', 'ANALISTA', 'uma_authorization', 'SISTEMA'])).toEqual(['ANALISTA']);
  });
});
