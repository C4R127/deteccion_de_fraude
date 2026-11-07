"""Configuración del generador (§6.4), validada con Pydantic.

Las credenciales del cliente OAuth no van en ``config.yaml``: el secreto se lee de la
variable de entorno ``GENERADOR_CLIENT_SECRET`` (definida en ``.env``).
"""

from pathlib import Path

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator

ESCENARIOS = ("TARJETA_CLONADA", "CARD_TESTING", "TOMA_DE_CUENTA", "COMPRA_ATIPICA", "USO_NOCTURNO")
SEGMENTOS = ("ESTANDAR", "PREMIUM", "JOVEN")


class OAuthConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    url_token: str = "http://localhost:8180/realms/fraude/protocol/openid-connect/token"
    client_id: str = "generador"
    variable_secreto: str = "GENERADOR_CLIENT_SECRET"


class Config(BaseModel):
    model_config = ConfigDict(extra="forbid")

    clientes: int = Field(default=500, ge=1)
    distribucion_segmentos: dict[str, float] = Field(
        default_factory=lambda: {"ESTANDAR": 0.6, "PREMIUM": 0.15, "JOVEN": 0.25})
    tps: float = Field(default=0.5, gt=0)
    # Minutos mínimos entre transacciones de una misma tarjeta en vivo (ADR 0015).
    espaciado_minutos: float = Field(default=15, ge=0)
    proporcion_fraude: float = Field(default=0.02, ge=0, le=1)
    pesos_escenarios: dict[str, float] = Field(
        default_factory=lambda: {e: 1.0 for e in ESCENARIOS})
    semilla: int = 42
    url_gateway: str = "http://localhost:8080"
    # La siembra usa /internal/v1, que no pasa por el gateway (§11).
    url_transacciones: str = "http://localhost:8081"
    oauth: OAuthConfig = Field(default_factory=OAuthConfig)

    @field_validator("distribucion_segmentos")
    @classmethod
    def _validar_segmentos(cls, valor: dict[str, float]) -> dict[str, float]:
        if set(valor) != set(SEGMENTOS):
            raise ValueError(f"los segmentos deben ser exactamente {SEGMENTOS}")
        if any(p < 0 for p in valor.values()) or abs(sum(valor.values()) - 1) > 1e-9:
            raise ValueError("las proporciones deben ser no negativas y sumar 1")
        return valor

    @field_validator("pesos_escenarios")
    @classmethod
    def _validar_escenarios(cls, valor: dict[str, float]) -> dict[str, float]:
        desconocidos = set(valor) - set(ESCENARIOS)
        if desconocidos:
            raise ValueError(f"escenarios desconocidos: {sorted(desconocidos)}")
        if any(p < 0 for p in valor.values()) or sum(valor.values()) <= 0:
            raise ValueError("los pesos deben ser no negativos y sumar más que 0")
        return valor


def cargar_config(ruta: Path) -> Config:
    with open(ruta, encoding="utf-8") as archivo:
        return Config(**(yaml.safe_load(archivo) or {}))
