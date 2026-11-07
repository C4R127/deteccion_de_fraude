"""Comando siembra: carga clientes, tarjetas, comercios y dispositivos en transacciones."""

from datetime import datetime, timezone

import httpx

from generador.cliente_api import ProveedorToken
from generador.modelos import Poblacion

RUTA_SIEMBRA = "/internal/v1/siembra"


def _utc(fecha: datetime) -> str:
    return fecha.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _decimal(valor) -> float | None:
    return None if valor is None else float(valor)


def payload_siembra(poblacion: Poblacion) -> dict:
    """Cuerpo de ``POST /internal/v1/siembra`` con los nombres de campo de la API."""
    return {
        "clientes": [
            {"id": str(c.id), "nombre": c.nombre, "segmento": c.segmento,
             "ciudadResidencia": c.ciudad_residencia, "fechaAlta": _utc(c.fecha_alta)}
            for c in poblacion.clientes
        ],
        "tarjetas": [
            {"id": str(t.id), "clienteId": str(t.cliente_id), "token": t.token,
             "ultimos4": t.ultimos4, "estado": t.estado, "fechaEmision": _utc(t.fecha_emision)}
            for t in poblacion.tarjetas
        ],
        "comercios": [
            {"id": c.id, "nombre": c.nombre, "mcc": c.mcc, "pais": c.pais, "ciudad": c.ciudad,
             "latitud": _decimal(c.latitud), "longitud": _decimal(c.longitud), "online": c.online}
            for c in poblacion.comercios
        ],
        "dispositivos": [
            {"huella": huella, "clienteId": str(c.id)}
            for c in poblacion.clientes for huella in c.dispositivos
        ],
    }


async def enviar_siembra(poblacion: Poblacion, url_transacciones: str, http: httpx.AsyncClient,
                         proveedor: ProveedorToken) -> dict:
    """La siembra va directo a transacciones (no pasa por el gateway) con un token SISTEMA."""
    respuesta = await http.post(url_transacciones.rstrip("/") + RUTA_SIEMBRA,
                                json=payload_siembra(poblacion), headers=await proveedor.cabeceras())
    respuesta.raise_for_status()
    return respuesta.json()
