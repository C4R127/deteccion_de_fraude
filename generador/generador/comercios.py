"""Ciudades y comercios ficticios: físicos por ciudad, cajeros y tiendas en línea."""

from dataclasses import dataclass
from decimal import Decimal

import numpy as np

from generador.modelos import Comercio

SEIS_DECIMALES = Decimal("0.000001")


@dataclass(frozen=True)
class Ciudad:
    nombre: str
    latitud: float
    longitud: float


CIUDADES = (
    Ciudad("Lima", -12.0464, -77.0428),
    Ciudad("Arequipa", -16.4090, -71.5375),
    Ciudad("Trujillo", -8.1116, -79.0287),
    Ciudad("Cusco", -13.5320, -71.9675),
    Ciudad("Piura", -5.1945, -80.6328),
    Ciudad("Chiclayo", -6.7714, -79.8409),
)

# Rubros de comercio físico habituales (MCC → nombre del rubro).
MCC_FISICOS = {
    "5411": "Supermercado",
    "5812": "Restaurante",
    "5541": "Grifo",
    "5912": "Farmacia",
    "5311": "Tienda por departamentos",
    "5651": "Tienda de ropa",
    "4111": "Transporte",
    "5732": "Electrónica",
}
# Rubros en línea habituales.
MCC_ONLINE = {
    "5969": "Tienda en línea",
    "4899": "Streaming",
    "5815": "Contenido digital",
    "4722": "Agencia de viajes",
}
# Rubros de riesgo (R06): existen para los escenarios de fraude, no para los hábitos.
MCC_RIESGO = {
    "7995": "Apuestas",
    "6051": "Cuasi efectivo / cripto",
    "4829": "Transferencias de dinero",
}
MCC_CAJERO = "6011"

COMERCIOS_POR_RUBRO_Y_CIUDAD = 3
CAJEROS_POR_CIUDAD = 4
COMERCIOS_ONLINE_POR_RUBRO = 4
COMERCIOS_RIESGO_POR_RUBRO = 2
# Dispersión de los comercios alrededor del centro de la ciudad (grados, ~5 km).
DISPERSION_GRADOS = 0.045


def _coordenada(valor: float) -> Decimal:
    return Decimal(repr(valor)).quantize(SEIS_DECIMALES)


def crear_comercios(rng: np.random.Generator) -> tuple[Comercio, ...]:
    comercios: list[Comercio] = []

    def siguiente_id() -> str:
        return f"COM-{len(comercios) + 1:05d}"

    for ciudad in CIUDADES:
        for mcc, rubro in MCC_FISICOS.items():
            for i in range(COMERCIOS_POR_RUBRO_Y_CIUDAD):
                comercios.append(_fisico(siguiente_id(), f"{rubro} {ciudad.nombre} {i + 1}",
                                         mcc, ciudad, rng))
        for i in range(CAJEROS_POR_CIUDAD):
            comercios.append(_fisico(siguiente_id(), f"Cajero {ciudad.nombre} {i + 1}",
                                     MCC_CAJERO, ciudad, rng))

    for mcc, rubro in (MCC_ONLINE | MCC_RIESGO).items():
        cantidad = COMERCIOS_RIESGO_POR_RUBRO if mcc in MCC_RIESGO else COMERCIOS_ONLINE_POR_RUBRO
        for i in range(cantidad):
            comercios.append(Comercio(id=siguiente_id(), nombre=f"{rubro} en línea {i + 1}",
                                      mcc=mcc, pais="PE", ciudad="Lima", latitud=None,
                                      longitud=None, online=True))
    return tuple(comercios)


def _fisico(id_: str, nombre: str, mcc: str, ciudad: Ciudad, rng: np.random.Generator) -> Comercio:
    lat = ciudad.latitud + rng.uniform(-DISPERSION_GRADOS, DISPERSION_GRADOS)
    lon = ciudad.longitud + rng.uniform(-DISPERSION_GRADOS, DISPERSION_GRADOS)
    return Comercio(id=id_, nombre=nombre, mcc=mcc, pais="PE", ciudad=ciudad.nombre,
                    latitud=_coordenada(lat), longitud=_coordenada(lon), online=False)
