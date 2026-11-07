import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RutasApp } from '@/rutas';
import type { Rol } from '@/auth/permisos';
import { renderizar } from '@/pruebas/renderizar';

function menu() {
  return within(screen.getByRole('navigation', { name: 'Menú principal' }));
}

describe('menú según rol (§10)', () => {
  it.each<Rol>(['ADMIN', 'SUPERVISOR'])('%s ve Reglas y Listas de bloqueo', (rol) => {
    renderizar(<RutasApp />, { roles: [rol], ruta: '/dashboard' });

    expect(menu().getByRole('link', { name: 'Reglas' })).toHaveAttribute('href', '/reglas');
    expect(menu().getByRole('link', { name: 'Listas de bloqueo' })).toHaveAttribute('href', '/listas-bloqueo');
  });

  it.each<Rol>(['ANALISTA', 'AUDITOR'])('%s no ve Reglas ni Listas de bloqueo', (rol) => {
    renderizar(<RutasApp />, { roles: [rol], ruta: '/dashboard' });

    expect(menu().queryByRole('link', { name: 'Reglas' })).not.toBeInTheDocument();
    expect(menu().queryByRole('link', { name: 'Listas de bloqueo' })).not.toBeInTheDocument();
  });

  it.each<Rol>(['ADMIN', 'SUPERVISOR', 'ANALISTA', 'AUDITOR'])('%s ve Dashboard, Casos y Métricas', (rol) => {
    renderizar(<RutasApp />, { roles: [rol], ruta: '/dashboard' });

    expect(menu().getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/dashboard');
    expect(menu().getByRole('link', { name: 'Casos' })).toHaveAttribute('href', '/casos');
    expect(menu().getByRole('link', { name: 'Métricas' })).toHaveAttribute('href', '/metricas');
  });

  it('muestra el usuario y sus roles', () => {
    renderizar(<RutasApp />, { roles: ['SUPERVISOR'], ruta: '/dashboard' });

    expect(screen.getByText('usuario-prueba')).toBeInTheDocument();
    expect(screen.getByText('SUPERVISOR')).toBeInTheDocument();
  });
});

describe('rutas protegidas', () => {
  it('un analista que abre /reglas ve que no tiene permiso', () => {
    renderizar(<RutasApp />, { roles: ['ANALISTA'], ruta: '/reglas' });

    expect(screen.getByRole('heading', { name: 'Sin permiso' })).toBeInTheDocument();
  });

  it('un supervisor abre /listas-bloqueo', () => {
    renderizar(<RutasApp />, { roles: ['SUPERVISOR'], ruta: '/listas-bloqueo' });

    expect(screen.getByRole('heading', { name: 'Listas de bloqueo' })).toBeInTheDocument();
  });

  it('la raíz lleva al dashboard', () => {
    renderizar(<RutasApp />, { roles: ['AUDITOR'], ruta: '/' });

    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('un usuario sin roles de la aplicación no ve el menú', () => {
    renderizar(<RutasApp />, { roles: [], ruta: '/casos' });

    expect(screen.getByRole('heading', { name: 'Sin acceso' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Menú principal' })).not.toBeInTheDocument();
  });
});
