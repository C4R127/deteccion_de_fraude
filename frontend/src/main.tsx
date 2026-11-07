import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { iniciarSesion } from '@/auth/keycloak';
import { SesionContext } from '@/auth/sesion';
import { RutasApp } from '@/rutas';
import './index.css';

const consultas = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, refetchOnWindowFocus: false } },
});

const raiz = createRoot(document.getElementById('root')!);

iniciarSesion()
  .then((sesion) => {
    raiz.render(
      <StrictMode>
        <SesionContext.Provider value={sesion}>
          <QueryClientProvider client={consultas}>
            <BrowserRouter>
              <RutasApp />
            </BrowserRouter>
          </QueryClientProvider>
        </SesionContext.Provider>
      </StrictMode>,
    );
  })
  .catch((error: unknown) => {
    console.error('No se pudo iniciar sesión', error);
    raiz.render(
      <p style={{ padding: 32, fontFamily: 'sans-serif' }}>
        No se pudo conectar con Keycloak. Revisa que la infraestructura esté levantada.
      </p>,
    );
  });
