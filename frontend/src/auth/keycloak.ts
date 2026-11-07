import Keycloak from 'keycloak-js';
import { rolesDeLaApp } from './permisos';
import type { Sesion } from './sesion';

// Cliente público "frontend" del realm "fraude" con PKCE (infra/keycloak/realm-fraude.json).
const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'fraude',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT ?? 'frontend',
});

/** Segundos de margen: si el token vence antes, se renueva antes de usarlo. */
const MARGEN_RENOVACION_S = 30;

/** Exige el inicio de sesión en Keycloak y devuelve la sesión del usuario. */
export async function iniciarSesion(): Promise<Sesion> {
  await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
  const datos = keycloak.tokenParsed ?? {};
  const rolesToken: string[] = (datos.realm_access?.roles as string[] | undefined) ?? [];
  return {
    usuario: (datos as { preferred_username?: string }).preferred_username ?? datos.sub ?? 'desconocido',
    roles: rolesDeLaApp(rolesToken),
    token: async () => {
      await keycloak.updateToken(MARGEN_RENOVACION_S);
      return keycloak.token!;
    },
    salir: () => {
      void keycloak.logout({ redirectUri: window.location.origin });
    },
  };
}
