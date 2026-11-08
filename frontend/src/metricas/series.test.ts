import { describe, expect, it } from 'vitest';
import { completarMinutos, porcentaje } from './series';

describe('serie de volumen por minuto', () => {
  it('rellena con ceros los minutos sin transacciones', () => {
    const serie = completarMinutos(
      [
        { minuto: '2026-11-01T12:00:00Z', total: 3, aprobar: 2, revisar: 1, rechazar: 0, respaldos: 1 },
        { minuto: '2026-11-01T12:03:00Z', total: 1, aprobar: 0, revisar: 0, rechazar: 1, respaldos: 0 },
      ],
      '2026-11-01T12:00:00Z',
      '2026-11-01T12:04:30Z',
    );

    expect(serie.map((m) => m.minuto)).toEqual([
      '2026-11-01T12:00:00.000Z', '2026-11-01T12:01:00.000Z', '2026-11-01T12:02:00.000Z',
      '2026-11-01T12:03:00.000Z', '2026-11-01T12:04:00.000Z',
    ]);
    expect(serie.map((m) => m.total)).toEqual([3, 0, 0, 1, 0]);
    expect(serie[3]!.rechazar).toBe(1);
  });

  it('empieza en el minuto de desde aunque no sea exacto', () => {
    const serie = completarMinutos([], '2026-11-01T12:00:40Z', '2026-11-01T12:02:10Z');
    expect(serie.map((m) => m.minuto)).toEqual([
      '2026-11-01T12:00:00.000Z', '2026-11-01T12:01:00.000Z', '2026-11-01T12:02:00.000Z',
    ]);
  });
});

describe('porcentaje', () => {
  it('muestra un guion cuando no hay denominador', () => {
    expect(porcentaje(null)).toBe('—');
    expect(porcentaje(83.33)).toBe('83.3 %');
    expect(porcentaje(80)).toBe('80.0 %');
  });
});
