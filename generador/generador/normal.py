"""Transacciones normales (sin fraude) según los hábitos de cada cliente."""

from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID
from zoneinfo import ZoneInfo

import numpy as np

from generador.clientes import uuid_determinista
from generador.comercios import MCC_CAJERO, MCC_ONLINE, MCC_RIESGO
from generador.geo import haversine_km
from generador.ids import GeneradorIds
from generador.modelos import Cliente, Comercio, Poblacion, Tarjeta, TransaccionSalida

LIMA = ZoneInfo("America/Lima")
CENTIMOS = Decimal("0.01")
MONTO_MINIMO = Decimal("1.00")
# Probabilidad de que una compra POS ocurra en otra ciudad (viaje legítimo).
PROBABILIDAD_POS_FUERA = 0.05
MONTOS_CAJERO = (50, 100, 100, 200, 200, 300, 400, 500)
# En vivo, una compra fuera de la ciudad solo si el viaje desde la última compra física
# es posible (ADR 0015); margen bajo los 800 km/h de R03.
VELOCIDAD_MAXIMA_VIAJE_KMH = 500.0


class IndiceComercios:
    """Comercios agrupados para elegir rápido por ciudad, rubro y canal."""

    def __init__(self, comercios: tuple[Comercio, ...]):
        self.fisicos: dict[tuple[str, str], list[Comercio]] = {}
        self.cajeros: dict[str, list[Comercio]] = {}
        self.online: dict[str, list[Comercio]] = {}
        # Tiendas en línea de rubros de riesgo (R06): solo las usan los escenarios de fraude.
        self.riesgo: dict[str, list[Comercio]] = {}
        self.ciudad_de: dict[str, str] = {c.id: c.ciudad for c in comercios if not c.online}
        for c in comercios:
            if c.online:
                if c.mcc in MCC_ONLINE:
                    self.online.setdefault(c.mcc, []).append(c)
                elif c.mcc in MCC_RIESGO:
                    self.riesgo.setdefault(c.mcc, []).append(c)
            elif c.mcc == MCC_CAJERO:
                self.cajeros.setdefault(c.ciudad, []).append(c)
            else:
                self.fisicos.setdefault((c.ciudad, c.mcc), []).append(c)
        self.ciudades = sorted(self.cajeros)


def generar_normal(poblacion: Poblacion, desde: datetime, hasta: datetime,
                   rng: np.random.Generator, ids: GeneradorIds | None = None) -> Iterator[TransaccionSalida]:
    """Genera transacciones en ``[desde, hasta)`` ordenadas por fecha, día por día.

    ``ids`` da los ``idExterno``; sin él salen de ``rng`` (solo para pruebas y ``seco``).
    """
    indice = IndiceComercios(poblacion.comercios)
    tarjeta_de = {t.cliente_id: t for t in poblacion.tarjetas}
    dia = desde.astimezone(LIMA).replace(hour=0, minute=0, second=0, microsecond=0)
    while dia < hasta:
        del_dia: list[TransaccionSalida] = []
        for cliente in poblacion.clientes:
            for _ in range(int(rng.poisson(cliente.transacciones_por_dia))):
                tx = _transaccion(cliente, tarjeta_de[cliente.id], dia, indice, rng, ids=ids)
                if desde <= tx.fecha < hasta:
                    del_dia.append(tx)
        del_dia.sort(key=lambda t: (t.fecha, str(t.idExterno)))
        yield from del_dia
        dia += timedelta(days=1)  # Lima no tiene horario de verano


class GeneradorVivo:
    """Transacciones normales con la fecha que se indique (modo ``vivo``).

    El modo vivo comprime el tiempo: a 20 tps con 500 clientes cada tarjeta compraría cada
    ~25 s y R02 y R03 saltarían en casi todo el tráfico normal. Por eso (ADR 0015) cada
    tarjeta espera ``espaciado`` entre transacciones y solo compra fuera de su ciudad si el
    viaje desde su última compra física es posible.
    """

    def __init__(self, poblacion: Poblacion, rng: np.random.Generator, ids: GeneradorIds | None = None,
                 espaciado: timedelta = timedelta(0)):
        self._poblacion = poblacion
        self._rng = rng
        self._ids = ids
        self._espaciado = espaciado
        self._indice = IndiceComercios(poblacion.comercios)
        self._tarjeta_de = {t.cliente_id: t for t in poblacion.tarjetas}
        self._posicion = {c.id: i for i, c in enumerate(poblacion.clientes)}
        self._pesos = np.array([c.transacciones_por_dia for c in poblacion.clientes])
        # Última transacción de cada cliente (epoch s; -inf = nunca) y última compra física.
        self._ultima = np.full(len(poblacion.clientes), -np.inf)
        self._ultima_fisica: dict[UUID, tuple[datetime, float, float, str]] = {}

    @property
    def indice(self) -> IndiceComercios:
        return self._indice

    def siguiente(self, fecha: datetime, excluidos: frozenset[UUID] = frozenset()) -> TransaccionSalida | None:
        """Una transacción de un cliente elegido según su frecuencia de compra.

        Solo se eligen clientes cuya última transacción tiene al menos ``espaciado`` y que no
        estén en ``excluidos``; si no queda ninguno, devuelve ``None``.
        """
        fecha = fecha.astimezone(timezone.utc).replace(microsecond=0)
        disponibles = self._ultima <= fecha.timestamp() - self._espaciado.total_seconds()
        for cliente_id in excluidos:
            disponibles[self._posicion[cliente_id]] = False
        pesos = np.where(disponibles, self._pesos, 0.0)
        if pesos.sum() <= 0:
            return None
        cliente = self._poblacion.clientes[int(self._rng.choice(len(pesos), p=pesos / pesos.sum()))]
        tarjeta = self._tarjeta_de[cliente.id]
        tx = _transaccion(cliente, tarjeta, None, self._indice, self._rng, fecha=fecha, ids=self._ids)
        if not self._viaje_posible(cliente.id, tx):
            # Sigue en la ciudad de su última compra física hasta que el viaje sea posible.
            tx = _transaccion(cliente, tarjeta, None, self._indice, self._rng, fecha=fecha, ids=self._ids,
                              ciudad_fija=self._ultima_fisica[cliente.id][3])
        self.registrar(cliente.id, tx)
        return tx

    def registrar(self, cliente_id: UUID, tx: TransaccionSalida) -> None:
        """Anota una transacción del cliente (también las de fraude) para el espaciado y los viajes."""
        posicion = self._posicion[cliente_id]
        self._ultima[posicion] = max(self._ultima[posicion], tx.fecha.timestamp())
        if tx.latitud is not None and tx.longitud is not None:
            self._ultima_fisica[cliente_id] = (tx.fecha, float(tx.latitud), float(tx.longitud),
                                               self._indice.ciudad_de[tx.comercioId])

    def _viaje_posible(self, cliente_id: UUID, tx: TransaccionSalida) -> bool:
        anterior = self._ultima_fisica.get(cliente_id)
        if anterior is None or tx.latitud is None or tx.longitud is None:
            return True
        fecha, lat, lon, _ = anterior
        distancia = haversine_km(lat, lon, float(tx.latitud), float(tx.longitud))
        horas = (tx.fecha - fecha).total_seconds() / 3600
        return distancia <= VELOCIDAD_MAXIMA_VIAJE_KMH * horas if horas > 0 else distancia <= 50


def _transaccion(cliente: Cliente, tarjeta: Tarjeta, dia_lima: datetime | None, indice: IndiceComercios,
                 rng: np.random.Generator, fecha: datetime | None = None,
                 ids: GeneradorIds | None = None, ciudad_fija: str | None = None) -> TransaccionSalida:
    """Si ``fecha`` es nula, la hora se elige entre las habituales del cliente en ``dia_lima``.

    Con ``ciudad_fija``, las compras POS y de cajero son en esa ciudad.
    """
    canales = [c for c, _ in cliente.canales_habituales]
    pesos = np.array([p for _, p in cliente.canales_habituales])
    canal = str(rng.choice(canales, p=pesos / pesos.sum()))

    if canal == "ECOMMERCE":
        mccs = [m for m in cliente.mccs_habituales if m in indice.online]
        comercio = _elegir(rng, indice.online[str(rng.choice(mccs))])
        dispositivo = str(rng.choice(cliente.dispositivos))
        monto = monto_compra(cliente, rng)
    elif canal == "ATM":
        comercio = _elegir(rng, indice.cajeros[ciudad_fija or cliente.ciudad_residencia])
        dispositivo = None
        monto = Decimal(int(rng.choice(MONTOS_CAJERO))).quantize(CENTIMOS)
    else:
        ciudad = cliente.ciudad_residencia
        if ciudad_fija is not None:
            ciudad = ciudad_fija
        elif rng.random() < PROBABILIDAD_POS_FUERA:
            ciudad = str(rng.choice([c for c in indice.ciudades if c != ciudad]))
        mccs = [m for m in cliente.mccs_habituales if (ciudad, m) in indice.fisicos]
        comercio = _elegir(rng, indice.fisicos[(ciudad, str(rng.choice(mccs)))])
        dispositivo = None
        monto = monto_compra(cliente, rng)

    if fecha is None:
        hora = int(rng.choice(cliente.horas_habituales))
        local = dia_lima.replace(hour=hora, minute=int(rng.integers(0, 60)),
                                 second=int(rng.integers(0, 60)))
        fecha = local.astimezone(timezone.utc)
    return TransaccionSalida(
        idExterno=ids.siguiente() if ids is not None else uuid_determinista(rng),
        tarjetaToken=tarjeta.token,
        comercioId=comercio.id,
        dispositivoHuella=dispositivo,
        monto=monto,
        moneda="PEN",
        canal=canal,
        pais=comercio.pais,
        latitud=comercio.latitud,
        longitud=comercio.longitud,
        fecha=fecha,
    )


def _elegir(rng: np.random.Generator, opciones: list[Comercio]) -> Comercio:
    return opciones[int(rng.integers(0, len(opciones)))]


def monto_compra(cliente: Cliente, rng: np.random.Generator) -> Decimal:
    """Monto con distribución log-normal de media y desviación del cliente."""
    media = float(cliente.gasto_media)
    desviacion = float(cliente.gasto_desviacion)
    sigma2 = np.log(1 + (desviacion / media) ** 2)
    valor = rng.lognormal(np.log(media) - sigma2 / 2, np.sqrt(sigma2))
    return max(MONTO_MINIMO, Decimal(repr(float(valor))).quantize(CENTIMOS))
