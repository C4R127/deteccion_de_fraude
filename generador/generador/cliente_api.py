"""Cliente de la API: token OAuth (client credentials) y envío de transacciones.

El secreto del cliente ``generador`` se lee de la variable de entorno configurada
(``GENERADOR_CLIENT_SECRET``) o, si no está, del ``.env`` de la raíz del repositorio.
"""

import asyncio
import os
import time
from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

import httpx

from generador.config import OAuthConfig
from generador.modelos import TransaccionSalida

ENV_RAIZ = Path(__file__).resolve().parents[2] / ".env"
RUTA_TRANSACCIONES = "/api/v1/transacciones"
# Margen para renovar el token antes de que expire.
MARGEN_TOKEN_S = 30
MAX_REINTENTOS = 8


def leer_secreto(variable: str, archivo_env: Path = ENV_RAIZ) -> str:
    valor = os.environ.get(variable)
    if valor:
        return valor
    if archivo_env.exists():
        for linea in archivo_env.read_text(encoding="utf-8").splitlines():
            linea = linea.strip()
            if linea and not linea.startswith("#") and "=" in linea:
                clave, _, dato = linea.partition("=")
                if clave.strip() == variable:
                    return dato.strip()
    raise RuntimeError(f"Falta {variable}: defínela en el entorno o en {archivo_env}")


class ProveedorToken:
    """Obtiene y reutiliza el token de client credentials hasta poco antes de que expire."""

    def __init__(self, oauth: OAuthConfig, http: httpx.AsyncClient):
        self._oauth = oauth
        self._http = http
        self._token: str | None = None
        self._expira = 0.0
        self._candado = asyncio.Lock()

    async def token(self, renovar: bool = False) -> str:
        async with self._candado:
            if renovar or self._token is None or time.monotonic() >= self._expira:
                respuesta = await self._http.post(self._oauth.url_token, data={
                    "grant_type": "client_credentials",
                    "client_id": self._oauth.client_id,
                    "client_secret": leer_secreto(self._oauth.variable_secreto),
                })
                respuesta.raise_for_status()
                datos = respuesta.json()
                self._token = datos["access_token"]
                self._expira = time.monotonic() + max(0, int(datos.get("expires_in", 60)) - MARGEN_TOKEN_S)
            return self._token

    async def cabeceras(self, renovar: bool = False) -> dict[str, str]:
        return {"Authorization": f"Bearer {await self.token(renovar)}"}


@dataclass
class Resumen:
    enviadas: int = 0
    errores: int = 0
    reintentos: int = 0
    respaldo: int = 0
    # Respuestas 200: el idExterno ya existía y la API devolvió la respuesta original.
    repetidas: int = 0
    decisiones: Counter = field(default_factory=Counter)

    def texto(self) -> str:
        decisiones = ", ".join(f"{k}={v}" for k, v in sorted(self.decisiones.items()))
        return (f"enviadas={self.enviadas} errores={self.errores} reintentos={self.reintentos} "
                f"respaldo={self.respaldo} repetidas={self.repetidas} {decisiones}").strip()


async def enviar_una(tx: TransaccionSalida, url_gateway: str, http: httpx.AsyncClient,
                     proveedor: ProveedorToken, resumen: Resumen, espera_base: float = 0.05) -> None:
    """Envía una transacción; reintenta con espera creciente ante 429 y renueva el token ante 401."""
    url = url_gateway.rstrip("/") + RUTA_TRANSACCIONES
    cuerpo = tx.a_json()
    renovado = False
    for intento in range(MAX_REINTENTOS):
        try:
            respuesta = await http.post(url, content=cuerpo, headers={
                **await proveedor.cabeceras(), "Content-Type": "application/json"})
        except httpx.HTTPError:
            resumen.errores += 1
            resumen.enviadas += 1
            return
        if respuesta.status_code == 429:
            resumen.reintentos += 1
            await asyncio.sleep(espera_base * (2 ** intento))
            continue
        if respuesta.status_code == 401 and not renovado:
            renovado = True
            await proveedor.token(renovar=True)
            continue
        resumen.enviadas += 1
        if respuesta.status_code == 200:
            resumen.repetidas += 1
        elif respuesta.status_code == 201:
            datos = respuesta.json()
            resumen.decisiones[datos["decision"]] += 1
            resumen.respaldo += 1 if datos.get("respaldo") else 0
        else:
            resumen.errores += 1
        return
    resumen.enviadas += 1
    resumen.errores += 1


async def enviar_transacciones(transacciones: Iterable[TransaccionSalida], url_gateway: str,
                               http: httpx.AsyncClient, proveedor: ProveedorToken,
                               concurrencia: int = 20, espera_base: float = 0.05) -> Resumen:
    """Envía todas las transacciones con a lo sumo ``concurrencia`` solicitudes en vuelo."""
    resumen = Resumen()
    semaforo = asyncio.Semaphore(concurrencia)
    pendientes: set[asyncio.Task] = set()

    async def una(tx: TransaccionSalida) -> None:
        try:
            await enviar_una(tx, url_gateway, http, proveedor, resumen, espera_base)
        finally:
            semaforo.release()

    for tx in transacciones:
        await semaforo.acquire()
        tarea = asyncio.create_task(una(tx))
        pendientes.add(tarea)
        tarea.add_done_callback(pendientes.discard)
    if pendientes:
        await asyncio.gather(*pendientes)
    return resumen
