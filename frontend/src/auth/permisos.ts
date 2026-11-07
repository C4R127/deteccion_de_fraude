/** Roles del realm que usa la interfaz (§10); SISTEMA es solo para el generador. */
export const ROLES = ['ADMIN', 'SUPERVISOR', 'ANALISTA', 'AUDITOR'] as const;
export type Rol = (typeof ROLES)[number];

/** Permisos de la tabla de §10. */
export type Permiso =
  | 'ver'
  | 'gestionarReglas'
  | 'gestionarListas'
  | 'asignarCasos'
  | 'trabajarCasos'
  | 'agregarNotas';

const PERMISOS: Record<Permiso, readonly Rol[]> = {
  ver: ['ADMIN', 'SUPERVISOR', 'ANALISTA', 'AUDITOR'],
  gestionarReglas: ['ADMIN', 'SUPERVISOR'],
  gestionarListas: ['ADMIN', 'SUPERVISOR'],
  asignarCasos: ['ADMIN', 'SUPERVISOR'],
  trabajarCasos: ['ADMIN', 'SUPERVISOR', 'ANALISTA'],
  agregarNotas: ['ADMIN', 'SUPERVISOR', 'ANALISTA'],
};

export function puede(roles: readonly Rol[], permiso: Permiso): boolean {
  return roles.some((rol) => PERMISOS[permiso].includes(rol));
}

/** Se queda con los roles de la aplicación entre todos los del token. */
export function rolesDeLaApp(rolesDelToken: readonly string[]): Rol[] {
  return ROLES.filter((rol) => rolesDelToken.includes(rol));
}
