"""Subida de ``verdad.jsonl`` a ``POST /api/v1/metricas/verdad`` (§6.2, ADR 0017).

La API guarda o actualiza cada etiqueta, así que volver a subir el mismo archivo no duplica.
"""

import json
from pathlib import Path
from uuid import UUID

import httpx

from generador.cliente_api import ProveedorToken

RUTA_VERDAD = "/api/v1/metricas/verdad"
TAMANO_LOTE = 1000


def leer_verdad(ruta: Path) -> list[dict[str, str]]:
    """Etiquetas del archivo, sin líneas vacías ni ``idExterno`` repetidos (gana la primera)."""
    etiquetas: dict[str, dict[str, str]] = {}
    with open(ruta, encoding="utf-8") as archivo:
        for numero, linea in enumerate(archivo, start=1):
            if not linea.strip():
                continue
            try:
                datos = json.loads(linea)
                id_externo = str(UUID(datos["idExterno"]))
                escenario = str(datos["escenario"]).strip()
            except (ValueError, KeyError, TypeError) as error:
                raise ValueError(f"línea {numero} inválida en {ruta}: {error}") from error
            if not escenario:
                raise ValueError(f"línea {numero} inválida en {ruta}: escenario vacío")
            etiquetas.setdefault(id_externo, {"idExterno": id_externo, "escenario": escenario})
    return list(etiquetas.values())


async def subir_verdad(etiquetas: list[dict[str, str]], url_gateway: str, http: httpx.AsyncClient,
                       proveedor: ProveedorToken) -> tuple[int, int]:
    """Sube por lotes; devuelve (recibidas, nuevas). Un error HTTP se propaga."""
    url = url_gateway.rstrip("/") + RUTA_VERDAD
    recibidas = nuevas = 0
    for inicio in range(0, len(etiquetas), TAMANO_LOTE):
        lote = etiquetas[inicio:inicio + TAMANO_LOTE]
        respuesta = await http.post(url, json=lote, headers=await proveedor.cabeceras())
        if respuesta.status_code == 401:
            respuesta = await http.post(url, json=lote, headers=await proveedor.cabeceras(renovar=True))
        respuesta.raise_for_status()
        datos = respuesta.json()
        recibidas += int(datos["recibidas"])
        nuevas += int(datos["nuevas"])
    return recibidas, nuevas
