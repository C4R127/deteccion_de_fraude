"""Identificadores externos de las transacciones.

Se derivan de (semilla, modo, inicio de la corrida) con un generador propio, separado del
que decide montos, comercios y horarios. Así una corrida se repite igual con los mismos
parámetros, pero dos corridas distintas (otro modo u otra ventana) nunca comparten
``idExterno``: si lo hicieran, la API las trataría como repetidas y no las registraría.
"""

import zlib
from datetime import datetime
from uuid import UUID

import numpy as np


class GeneradorIds:

    def __init__(self, semilla: int, modo: str, inicio: datetime):
        semilla_ids = [semilla, zlib.crc32(modo.encode("utf-8")), int(inicio.timestamp() * 1000)]
        self._rng = np.random.default_rng(semilla_ids)

    def siguiente(self) -> UUID:
        return UUID(bytes=self._rng.bytes(16), version=4)
