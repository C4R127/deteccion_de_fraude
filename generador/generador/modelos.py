"""Modelos de la población ficticia y de la transacción que se envía a la API."""

import json
from datetime import datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, field_serializer

Segmento = Literal["ESTANDAR", "PREMIUM", "JOVEN"]
CanalTx = Literal["POS", "ECOMMERCE", "ATM"]


class Tarjeta(BaseModel):
    """Solo token y últimos 4 dígitos: nunca un número de tarjeta completo."""

    model_config = ConfigDict(frozen=True)

    id: UUID
    cliente_id: UUID
    token: str
    ultimos4: str
    estado: Literal["ACTIVA", "BLOQUEADA"] = "ACTIVA"
    fecha_emision: datetime


class Comercio(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: str
    nombre: str
    mcc: str
    pais: str
    ciudad: str
    latitud: Decimal | None
    longitud: Decimal | None
    online: bool


class Cliente(BaseModel):
    """Cliente con sus hábitos (§6.1): gasto, horas, rubros, canales y dispositivos."""

    model_config = ConfigDict(frozen=True)

    id: UUID
    nombre: str
    segmento: Segmento
    ciudad_residencia: str
    fecha_alta: datetime
    gasto_media: Decimal
    gasto_desviacion: Decimal
    transacciones_por_dia: float
    horas_habituales: tuple[int, ...]
    mccs_habituales: tuple[str, ...]
    canales_habituales: tuple[tuple[CanalTx, float], ...]
    dispositivos: tuple[str, ...]


class Poblacion(BaseModel):
    model_config = ConfigDict(frozen=True)

    clientes: tuple[Cliente, ...]
    tarjetas: tuple[Tarjeta, ...]
    comercios: tuple[Comercio, ...]


class TransaccionSalida(BaseModel):
    """Cuerpo exacto de ``POST /api/v1/transacciones`` (§11)."""

    model_config = ConfigDict(frozen=True)

    idExterno: UUID
    tarjetaToken: str
    comercioId: str
    dispositivoHuella: str | None
    monto: Decimal
    moneda: Literal["PEN"]
    canal: CanalTx
    pais: str
    latitud: Decimal | None
    longitud: Decimal | None
    fecha: datetime

    @field_serializer("monto", "latitud", "longitud", when_used="json")
    def _decimal_como_numero(self, valor: Decimal | None) -> float | None:
        # JSON no tiene decimales exactos; con 2 (monto) o 6 (coordenadas) cifras
        # decimales el float conserva el valor y la API lo lee como BigDecimal.
        return None if valor is None else float(valor)

    @field_serializer("fecha", when_used="json")
    def _fecha_utc(self, valor: datetime) -> str:
        return valor.strftime("%Y-%m-%dT%H:%M:%SZ")

    def a_json(self) -> str:
        return json.dumps(self.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":"))
