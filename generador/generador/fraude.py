"""Escenarios de fraude (§6.3) y su mezcla con el tráfico normal en el modo ``vivo``.

Cada escenario se planifica completo al empezar: una lista de transacciones con su fecha.
El mezclador envía la primera de inmediato y las demás cuando llega su hora. Las
fraudulentas se marcan y se escriben en ``verdad.jsonl``.

Decisiones de la ADR 0015 para que cada escenario llegue al umbral de 40 sin cambiar reglas:

- ``CARD_TESTING`` usa una huella nueva en cada intento: perfiles aprende el dispositivo de
  toda transacción APROBAR, así que con una sola huella R05 dejaría de sumar desde la 2.ª.
  Desde la 6.ª compra: R02 + R05 = 55.
- ``USO_NOCTURNO`` compra en rubros de riesgo desde un dispositivo nuevo:
  R07 + R05 + R06 = 50 (después R05 + R06 = 40). Solo se elige de madrugada en Lima.
"""

import heapq
import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from types import TracebackType
from uuid import UUID
from zoneinfo import ZoneInfo

import numpy as np

from generador.comercios import CIUDADES
from generador.config import Config
from generador.geo import haversine_km
from generador.ids import GeneradorIds
from generador.modelos import CanalTx, Cliente, Comercio, Poblacion, Tarjeta, TransaccionSalida
from generador.normal import GeneradorVivo, IndiceComercios, monto_compra

__all__ = ["EscritorVerdad", "MezcladorVivo", "Planificada", "escenarios_elegibles", "es_madrugada",
           "generar_escenario", "haversine_km", "probabilidad_inicio"]

LIMA = ZoneInfo("America/Lima")
CENTIMOS = Decimal("0.01")
# R07: de 02:00 a 04:59 en Lima.
HORAS_MADRUGADA = range(2, 5)
# TARJETA_CLONADA: ciudad a esta distancia o más, de 2 a 10 minutos después de la compra local.
DISTANCIA_MINIMA_CLONADA_KM = 400.0
# Monto de TOMA_DE_CUENTA y COMPRA_ATIPICA, en veces el gasto medio (R01 pide más de 5).
FACTOR_MONTO_ALTO = (8.0, 15.0)
# (transacciones totales, fraudulentas) esperadas por escenario, para la proporción de fraude.
TAMANO_ESPERADO = {
    "TARJETA_CLONADA": (2.0, 1.0),
    "CARD_TESTING": (9.0, 9.0),
    "TOMA_DE_CUENTA": (1.0, 1.0),
    "COMPRA_ATIPICA": (1.0, 1.0),
    "USO_NOCTURNO": (3.0, 3.0),
}


@dataclass(frozen=True)
class Planificada:
    """Transacción a enviar con el escenario al que pertenece (nulo si es tráfico normal).

    ``fraude`` distingue, dentro de un escenario, las fraudulentas de las legítimas (la
    compra local de TARJETA_CLONADA es del titular).
    """

    tx: TransaccionSalida
    escenario: str | None
    fraude: bool = False


def es_madrugada(fecha: datetime) -> bool:
    return fecha.astimezone(LIMA).hour in HORAS_MADRUGADA


def escenarios_elegibles(pesos: dict[str, float], ahora: datetime) -> dict[str, float]:
    """Escenarios con peso positivo que pueden ocurrir en ``ahora`` (USO_NOCTURNO, solo de madrugada)."""
    return {e: p for e, p in pesos.items()
            if p > 0 and (e != "USO_NOCTURNO" or es_madrugada(ahora))}


def probabilidad_inicio(proporcion: float, pesos: dict[str, float]) -> float:
    """Probabilidad de empezar un escenario en un turno libre para que ``proporcion`` de lo enviado sea fraude.

    Por turno libre se envían en promedio ``1 - q + q·N`` transacciones (N: tamaño medio del
    escenario) y ``q·F`` fraudulentas (F: fraudulentas medias), de donde
    ``q = proporcion / (F + proporcion·(1 - N))``.
    """
    if proporcion <= 0 or not pesos:
        return 0.0
    total = sum(pesos.values())
    tamano = sum(p * TAMANO_ESPERADO[e][0] for e, p in pesos.items()) / total
    fraudes = sum(p * TAMANO_ESPERADO[e][1] for e, p in pesos.items()) / total
    return min(1.0, proporcion / (fraudes + proporcion * (1 - tamano)))


def generar_escenario(escenario: str, cliente: Cliente, tarjeta: Tarjeta, inicio: datetime,
                      indice: IndiceComercios, rng: np.random.Generator,
                      ids: GeneradorIds) -> list[Planificada]:
    """Planifica las transacciones del escenario empezando en ``inicio``, ordenadas por fecha."""
    inicio = inicio.astimezone(timezone.utc).replace(microsecond=0)
    generadores = {
        "TARJETA_CLONADA": _tarjeta_clonada,
        "CARD_TESTING": _card_testing,
        "TOMA_DE_CUENTA": _toma_de_cuenta,
        "COMPRA_ATIPICA": _compra_atipica,
        "USO_NOCTURNO": _uso_nocturno,
    }
    return generadores[escenario](_Contexto(cliente, tarjeta, indice, rng, ids), inicio)


@dataclass(frozen=True)
class _Contexto:
    cliente: Cliente
    tarjeta: Tarjeta
    indice: IndiceComercios
    rng: np.random.Generator
    ids: GeneradorIds

    def tx(self, comercio: Comercio, canal: CanalTx, dispositivo: str | None, monto: Decimal,
           fecha: datetime) -> TransaccionSalida:
        return TransaccionSalida(
            idExterno=self.ids.siguiente(), tarjetaToken=self.tarjeta.token, comercioId=comercio.id,
            dispositivoHuella=dispositivo, monto=monto, moneda="PEN", canal=canal, pais=comercio.pais,
            latitud=comercio.latitud, longitud=comercio.longitud, fecha=fecha)

    def elegir(self, opciones: list[Comercio]) -> Comercio:
        return opciones[int(self.rng.integers(0, len(opciones)))]

    def huella_nueva(self) -> str:
        return f"dev_{self.rng.bytes(6).hex()}"

    def segundos(self, minimo: float, maximo: float) -> timedelta:
        return timedelta(seconds=int(self.rng.uniform(minimo, maximo)))

    def monto_alto(self) -> Decimal:
        factor = Decimal(repr(float(self.rng.uniform(*FACTOR_MONTO_ALTO))))
        return (self.cliente.gasto_media * factor).quantize(CENTIMOS)

    def en_linea(self) -> list[Comercio]:
        return [c for lista in self.indice.online.values() for c in lista]

    def de_riesgo(self) -> list[Comercio]:
        return [c for lista in self.indice.riesgo.values() for c in lista]


def _tarjeta_clonada(ctx: _Contexto, inicio: datetime) -> list[Planificada]:
    """R03: compra local legítima y, minutos después, una compra POS en una ciudad lejana."""
    casa = ctx.cliente.ciudad_residencia
    mccs = [m for m in ctx.cliente.mccs_habituales if (casa, m) in ctx.indice.fisicos]
    local = ctx.elegir(ctx.indice.fisicos[(casa, str(ctx.rng.choice(mccs)))])

    origen = next(c for c in CIUDADES if c.nombre == casa)
    lejanas = [c.nombre for c in CIUDADES
               if haversine_km(origen.latitud, origen.longitud, c.latitud, c.longitud) >= DISTANCIA_MINIMA_CLONADA_KM]
    destino = str(ctx.rng.choice(lejanas))
    rubros = sorted(m for (ciudad, m) in ctx.indice.fisicos if ciudad == destino)
    lejano = ctx.elegir(ctx.indice.fisicos[(destino, str(ctx.rng.choice(rubros)))])

    monto_lejano = (monto_compra(ctx.cliente, ctx.rng)
                    * Decimal(repr(float(ctx.rng.uniform(1, 3))))).quantize(CENTIMOS)
    return [
        Planificada(ctx.tx(local, "POS", None, monto_compra(ctx.cliente, ctx.rng), inicio), "TARJETA_CLONADA"),
        Planificada(ctx.tx(lejano, "POS", None, monto_lejano, inicio + ctx.segundos(120, 600)),
                    "TARJETA_CLONADA", fraude=True),
    ]


def _card_testing(ctx: _Contexto, inicio: datetime) -> list[Planificada]:
    """R02 (+ R05): 6 a 12 compras en línea de 1 a 10 PEN en menos de 10 minutos, huella nueva en cada una."""
    comercio = ctx.elegir(ctx.en_linea())
    planes = []
    fecha = inicio
    for i in range(int(ctx.rng.integers(6, 13))):
        if i > 0:
            # 11 esperas de a lo sumo 45 s: menos de 10 minutos en total.
            fecha += ctx.segundos(15, 45)
        monto = Decimal(repr(float(ctx.rng.uniform(1, 10)))).quantize(CENTIMOS)
        planes.append(Planificada(ctx.tx(comercio, "ECOMMERCE", ctx.huella_nueva(), monto, fecha),
                                  "CARD_TESTING", fraude=True))
    return planes


def _toma_de_cuenta(ctx: _Contexto, inicio: datetime) -> list[Planificada]:
    """R05 + R01: dispositivo nuevo y monto mayor a 5 veces el promedio."""
    comercio = ctx.elegir(ctx.en_linea())
    return [Planificada(ctx.tx(comercio, "ECOMMERCE", ctx.huella_nueva(), ctx.monto_alto(), inicio),
                        "TOMA_DE_CUENTA", fraude=True)]


def _compra_atipica(ctx: _Contexto, inicio: datetime) -> list[Planificada]:
    """R01 + R06: monto muy alto en un rubro de riesgo nunca usado, desde un dispositivo conocido."""
    comercio = ctx.elegir(ctx.de_riesgo())
    dispositivo = str(ctx.rng.choice(ctx.cliente.dispositivos))
    return [Planificada(ctx.tx(comercio, "ECOMMERCE", dispositivo, ctx.monto_alto(), inicio),
                        "COMPRA_ATIPICA", fraude=True)]


def _uso_nocturno(ctx: _Contexto, inicio: datetime) -> list[Planificada]:
    """R07 + R05 + R06: 2 a 4 compras de madrugada en rubros de riesgo desde un dispositivo nuevo."""
    huella = ctx.huella_nueva()
    planes = []
    fecha = inicio
    for i in range(int(ctx.rng.integers(2, 5))):
        if i > 0:
            fecha += ctx.segundos(180, 1200)
        tx = ctx.tx(ctx.elegir(ctx.de_riesgo()), "ECOMMERCE", huella, monto_compra(ctx.cliente, ctx.rng), fecha)
        if i > 0 and not es_madrugada(fecha):
            break
        planes.append(Planificada(tx, "USO_NOCTURNO", fraude=True))
    return planes


class MezcladorVivo:
    """Decide turno a turno qué enviar en ``vivo``: una transacción de escenario que ya toca,
    el inicio de un escenario nuevo o una transacción normal.

    Un cliente con un escenario en curso no recibe tráfico normal ni otro escenario hasta
    terminarlo. Devuelve ``None`` si no hay cliente disponible (tps mayor de lo que admite el
    espaciado).
    """

    def __init__(self, poblacion: Poblacion, config: Config, rng: np.random.Generator, ids: GeneradorIds):
        self._poblacion = poblacion
        self._config = config
        self._rng = rng
        self._ids = ids
        self._normal = GeneradorVivo(poblacion, rng, ids, espaciado=timedelta(minutes=config.espaciado_minutos))
        self._tarjeta_de = {t.cliente_id: t for t in poblacion.tarjetas}
        self._pendientes: list[tuple[float, int, UUID, Planificada]] = []
        self._turno = 0
        self._activos: Counter[UUID] = Counter()

    def siguiente(self, ahora: datetime) -> Planificada | None:
        ahora = ahora.astimezone(timezone.utc).replace(microsecond=0)
        if self._pendientes and self._pendientes[0][0] <= ahora.timestamp():
            _, _, cliente_id, plan = heapq.heappop(self._pendientes)
            self._activos[cliente_id] -= 1
            if self._activos[cliente_id] <= 0:
                del self._activos[cliente_id]
            self._normal.registrar(cliente_id, plan.tx)
            return plan

        elegibles = escenarios_elegibles(self._config.pesos_escenarios, ahora)
        if elegibles and self._rng.random() < probabilidad_inicio(self._config.proporcion_fraude, elegibles):
            plan = self._iniciar(elegibles, ahora)
            if plan is not None:
                return plan

        tx = self._normal.siguiente(ahora, frozenset(self._activos))
        return None if tx is None else Planificada(tx, None)

    def _iniciar(self, elegibles: dict[str, float], ahora: datetime) -> Planificada | None:
        nombres = sorted(elegibles)
        pesos = np.array([elegibles[n] for n in nombres])
        escenario = nombres[int(self._rng.choice(len(nombres), p=pesos / pesos.sum()))]
        candidatos = [c for c in self._poblacion.clientes if c.id not in self._activos]
        if not candidatos:
            return None
        cliente = candidatos[int(self._rng.integers(0, len(candidatos)))]
        primero, *resto = generar_escenario(escenario, cliente, self._tarjeta_de[cliente.id], ahora,
                                            self._normal.indice, self._rng, self._ids)
        self._normal.registrar(cliente.id, primero.tx)
        for plan in resto:
            self._turno += 1
            self._activos[cliente.id] += 1
            heapq.heappush(self._pendientes, (plan.tx.fecha.timestamp(), self._turno, cliente.id, plan))
        return primero


class EscritorVerdad:
    """Agrega a ``verdad.jsonl`` una línea ``{"idExterno": ..., "escenario": ...}`` por fraude (§6.3)."""

    def __init__(self, ruta: Path):
        self._ruta = ruta
        self._archivo = None

    def __enter__(self) -> "EscritorVerdad":
        self._ruta.parent.mkdir(parents=True, exist_ok=True)
        self._archivo = open(self._ruta, "a", encoding="utf-8", newline="\n")
        return self

    def __exit__(self, tipo: type[BaseException] | None, error: BaseException | None,
                 traza: TracebackType | None) -> None:
        if self._archivo is not None:
            self._archivo.close()
            self._archivo = None

    def registrar(self, id_externo: UUID | str, escenario: str) -> None:
        linea = json.dumps({"idExterno": str(id_externo), "escenario": escenario}, ensure_ascii=False)
        self._archivo.write(linea + "\n")
        # Una corrida cortada con Ctrl+C no pierde las líneas ya enviadas.
        self._archivo.flush()
