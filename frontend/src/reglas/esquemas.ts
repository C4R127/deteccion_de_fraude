import { z } from 'zod';
import type { TipoBloqueo, ValorParametro, VersionRegla } from './tipos';

export const MENSAJE_POSITIVO = 'Debe ser un número mayor que 0.';
export const MENSAJE_HORA = 'Hora entera entre 0 y 23.';
export const MENSAJE_PUNTOS = 'Entero entre 0 y 100.';

/** Los parámetros de hora (R07) admiten 0; el resto de los numéricos debe ser positivo. */
const ES_HORA = /^hora/;

export type TipoParametro = 'numero' | 'hora' | 'lista' | 'texto';

export function tipoParametro(clave: string, valor: ValorParametro): TipoParametro {
  if (typeof valor === 'number') return ES_HORA.test(clave) ? 'hora' : 'numero';
  if (Array.isArray(valor)) return 'lista';
  return 'texto';
}

/** Valores del formulario: todo texto, como lo escribe la persona. */
export interface FormularioRegla {
  nombre: string;
  descripcion: string;
  puntos: string;
  activa: boolean;
  parametros: Record<string, string>;
}

/** Cuerpo de {@code PUT /api/v1/reglas/{codigo}}, con cada parámetro en su tipo. */
export interface ReglaEditada {
  nombre: string;
  descripcion: string;
  puntos: number;
  activa: boolean;
  parametros: Record<string, ValorParametro>;
}

function esquemaParametro(tipo: TipoParametro) {
  switch (tipo) {
    case 'numero':
      return z.string().trim()
        .refine((v) => v !== '' && Number.isFinite(Number(v)) && Number(v) > 0, MENSAJE_POSITIVO)
        .transform(Number);
    case 'hora':
      return z.string().trim().regex(/^\d{1,2}$/, MENSAJE_HORA).transform(Number)
        .refine((n) => n <= 23, MENSAJE_HORA);
    case 'lista':
      return z.string()
        .transform((v) => v.split(',').map((s) => s.trim()).filter(Boolean))
        .refine((lista) => lista.length > 0, 'Al menos un valor.');
    case 'texto':
      return z.string().trim().min(1, 'Obligatorio.');
  }
}

/** El esquema depende de la regla: los parámetros y sus tipos salen de la versión vigente. */
export function esquemaRegla(regla: VersionRegla) {
  const parametros: Record<string, z.ZodTypeAny> = {};
  for (const [clave, valor] of Object.entries(regla.parametros)) {
    parametros[clave] = esquemaParametro(tipoParametro(clave, valor));
  }
  return z.object({
    nombre: z.string().trim().min(1, 'Obligatorio.'),
    descripcion: z.string().trim().min(1, 'Obligatorio.'),
    puntos: z.string().trim().regex(/^\d{1,3}$/, MENSAJE_PUNTOS).transform(Number)
      .refine((n) => n <= 100, MENSAJE_PUNTOS),
    activa: z.boolean(),
    parametros: z.object(parametros),
  });
}

export function valoresIniciales(regla: VersionRegla): FormularioRegla {
  return {
    nombre: regla.nombre,
    descripcion: regla.descripcion,
    puntos: String(regla.puntos),
    activa: regla.activa,
    parametros: Object.fromEntries(Object.entries(regla.parametros)
      .map(([clave, valor]) => [clave, Array.isArray(valor) ? valor.join(', ') : String(valor)])),
  };
}

// --- Listas de bloqueo ---

export interface FormularioBloqueo {
  tipo: TipoBloqueo;
  valor: string;
  motivo: string;
  /** {@code datetime-local} en hora de Lima, o vacío. */
  vigenteHasta: string;
}

export interface BloqueoNuevo {
  tipo: TipoBloqueo;
  valor: string;
  motivo: string;
  vigenteHasta: string | null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** {@code yyyy-mm-ddThh:mm} en Lima (UTC-5, sin horario de verano) a instante UTC ISO. */
export function limaLocalAIso(valor: string): string {
  const [fecha, hora] = valor.split('T');
  const [anio, mes, dia] = fecha!.split('-').map(Number);
  const [hh, mm] = hora!.split(':').map(Number);
  return new Date(Date.UTC(anio!, mes! - 1, dia!, hh! + 5, mm!)).toISOString();
}

export const esquemaBloqueo = z.object({
  tipo: z.enum(['TARJETA', 'DISPOSITIVO', 'COMERCIO']),
  valor: z.string().trim().min(1, 'Indica el valor a bloquear.'),
  motivo: z.string().trim().min(1, 'Indica el motivo.'),
  vigenteHasta: z.string(),
}).superRefine((f, ctx) => {
  if (f.tipo === 'TARJETA' && f.valor && !UUID.test(f.valor)) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['valor'],
      message: 'La tarjeta se identifica por su id (UUID), no por su número.' });
  }
  if (f.vigenteHasta && new Date(limaLocalAIso(f.vigenteHasta)) <= new Date()) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['vigenteHasta'], message: 'Debe ser una fecha futura.' });
  }
}).transform((f): BloqueoNuevo => ({
  tipo: f.tipo,
  valor: f.valor,
  motivo: f.motivo,
  vigenteHasta: f.vigenteHasta ? limaLocalAIso(f.vigenteHasta) : null,
}));
