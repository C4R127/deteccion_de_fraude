export type ValorParametro = number | string | string[];

/** Versión de una regla ({@code GET/PUT /api/v1/reglas}). */
export interface VersionRegla {
  codigo: string;
  nombre: string;
  descripcion: string;
  parametros: Record<string, ValorParametro>;
  puntos: number;
  activa: boolean;
  version: number;
  vigente: boolean | null;
  creadaEn: string | null;
  creadaPor: string | null;
}

export type TipoBloqueo = 'TARJETA' | 'DISPOSITIVO' | 'COMERCIO';

/** Entrada de {@code GET /api/v1/listas-bloqueo}. */
export interface EntradaLista {
  id: string;
  tipo: TipoBloqueo;
  valor: string;
  motivo: string;
  origen: 'MANUAL' | 'CASO';
  vigenteHasta: string | null;
  creadaEn: string;
  creadaPor: string;
}
