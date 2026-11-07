export type EstadoCaso = 'NUEVO' | 'ASIGNADO' | 'EN_INVESTIGACION' | 'CERRADO';
export type Prioridad = 'ALTA' | 'MEDIA';
export type Resolucion = 'FRAUDE_CONFIRMADO' | 'FALSO_POSITIVO';
export type Decision = 'APROBAR' | 'REVISAR' | 'RECHAZAR';

export interface ReglaDisparada {
  codigo: string;
  puntos: number;
  detalle: string;
}

/** Caso de {@code GET /api/v1/casos} y de {@code /topic/casos}. */
export interface Caso {
  id: string;
  transaccionId: string;
  tarjetaId: string;
  clienteId: string;
  dispositivoHuella: string | null;
  monto: number;
  score: number;
  decision: Decision;
  reglasDisparadas: ReglaDisparada[];
  prioridad: Prioridad;
  estado: EstadoCaso;
  analistaId: string | null;
  creadoEn: string;
  cerradoEn: string | null;
  resolucion: Resolucion | null;
}

export interface Nota {
  id: string;
  autorId: string;
  texto: string;
  creadaEn: string;
}

export interface Historial {
  estadoAnterior: EstadoCaso | null;
  estadoNuevo: EstadoCaso;
  usuarioId: string;
  fecha: string;
  comentario: string | null;
}

export interface CasoDetalle extends Caso {
  notas: Nota[];
  historial: Historial[];
}

export interface PaginaCasos {
  contenido: Caso[];
  pagina: number;
  tamano: number;
  total: number;
}

export interface FiltrosCasos {
  estado?: EstadoCaso;
  prioridad?: Prioridad;
  analista?: string;
  /** Fecha local (yyyy-mm-dd) en Lima. */
  desde?: string;
  hasta?: string;
}

/** Cliente de transacciones; el nombre llega enmascarado para AUDITOR (ADR 0016). */
export interface Cliente {
  id: string;
  nombre: string;
  segmento: string;
  ciudadResidencia: string;
  fechaAlta: string;
}

export interface TransaccionResumen {
  id: string;
  idExterno: string;
  fecha: string;
  monto: number;
  moneda: string;
  canal: 'POS' | 'ECOMMERCE' | 'ATM';
  comercioId: string;
  comercioNombre: string;
  mcc: string;
  ciudad: string;
  pais: string;
  latitud: number | null;
  longitud: number | null;
  ultimos4: string;
  dispositivoHuella: string | null;
  decision: Decision | null;
  score: number | null;
  respaldo: boolean;
}

export interface Perfil {
  clienteId: string;
  n30d: number;
  promedio30d: number;
  desviacion30d: number;
  paises: string[];
  mccs: string[];
  horas: number[];
  dispositivos: string[];
  actualizado: string;
}
