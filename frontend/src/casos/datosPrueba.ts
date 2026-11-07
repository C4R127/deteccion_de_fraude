import type { Caso, CasoDetalle } from './tipos';

export function casoDePrueba(cambios: Partial<Caso> = {}): Caso {
  return {
    id: 'caso-1',
    transaccionId: 'tx-1',
    tarjetaId: 'tarjeta-1',
    clienteId: 'cliente-1',
    dispositivoHuella: 'dev_abc',
    monto: 2450,
    score: 55,
    decision: 'REVISAR',
    reglasDisparadas: [{ codigo: 'R01', puntos: 40, detalle: 'monto 2450.00 PEN, promedio 180.00 PEN' }],
    prioridad: 'MEDIA',
    estado: 'NUEVO',
    analistaId: null,
    creadoEn: '2026-11-07T15:30:00Z',
    cerradoEn: null,
    resolucion: null,
    ...cambios,
  };
}

export function detalleDePrueba(cambios: Partial<Caso> = {}): CasoDetalle {
  return {
    ...casoDePrueba(cambios),
    notas: [],
    historial: [
      { estadoAnterior: null, estadoNuevo: 'NUEVO', usuarioId: 'sistema', fecha: '2026-11-07T15:30:00Z', comentario: null },
    ],
  };
}
