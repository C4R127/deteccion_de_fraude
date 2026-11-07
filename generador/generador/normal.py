"""Transacciones normales (sin fraude) según los hábitos de cada cliente."""

from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

import numpy as np

from generador.clientes import uuid_determinista
from generador.comercios import MCC_CAJERO, MCC_ONLINE
from generador.ids import GeneradorIds
from generador.modelos import Cliente, Comercio, Poblacion, Tarjeta, TransaccionSalida

LIMA = ZoneInfo("America/Lima")
CENTIMOS = Decimal("0.01")
MONTO_MINIMO = Decimal("1.00")
# Probabilidad de que una compra POS ocurra en otra ciudad (viaje legítimo).
PROBABILIDAD_POS_FUERA = 0.05
MONTOS_CAJERO = (50, 100, 100, 200, 200, 300, 400, 500)


class _Indice:
    """Comercios agrupados para elegir rápido por ciudad, rubro y canal."""

    def __init__(self, comercios: tuple[Comercio, ...]):
        self.fisicos: dict[tuple[str, str], list[Comercio]] = {}
        self.cajeros: dict[str, list[Comercio]] = {}
        self.online: dict[str, list[Comercio]] = {}
        for c in comercios:
            if c.online:
                if c.mcc in MCC_ONLINE:
                    self.online.setdefault(c.mcc, []).append(c)
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
    indice = _Indice(poblacion.comercios)
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
    """Transacciones normales con la fecha que se indique (modo ``vivo``)."""

    def __init__(self, poblacion: Poblacion, rng: np.random.Generator, ids: GeneradorIds | None = None):
        self._poblacion = poblacion
        self._rng = rng
        self._ids = ids
        self._indice = _Indice(poblacion.comercios)
        self._tarjeta_de = {t.cliente_id: t for t in poblacion.tarjetas}
        pesos = np.array([c.transacciones_por_dia for c in poblacion.clientes])
        self._pesos = pesos / pesos.sum()

    def siguiente(self, fecha: datetime) -> TransaccionSalida:
        """Una transacción de un cliente elegido según su frecuencia de compra."""
        cliente = self._poblacion.clientes[int(self._rng.choice(len(self._poblacion.clientes), p=self._pesos))]
        return _transaccion(cliente, self._tarjeta_de[cliente.id], None, self._indice, self._rng,
                            fecha=fecha.astimezone(timezone.utc).replace(microsecond=0), ids=self._ids)


def _transaccion(cliente: Cliente, tarjeta: Tarjeta, dia_lima: datetime | None, indice: _Indice,
                 rng: np.random.Generator, fecha: datetime | None = None,
                 ids: GeneradorIds | None = None) -> TransaccionSalida:
    """Si ``fecha`` es nula, la hora se elige entre las habituales del cliente en ``dia_lima``."""
    canales = [c for c, _ in cliente.canales_habituales]
    pesos = np.array([p for _, p in cliente.canales_habituales])
    canal = str(rng.choice(canales, p=pesos / pesos.sum()))

    if canal == "ECOMMERCE":
        mccs = [m for m in cliente.mccs_habituales if m in indice.online]
        comercio = _elegir(rng, indice.online[str(rng.choice(mccs))])
        dispositivo = str(rng.choice(cliente.dispositivos))
        monto = _monto_compra(cliente, rng)
    elif canal == "ATM":
        comercio = _elegir(rng, indice.cajeros[cliente.ciudad_residencia])
        dispositivo = None
        monto = Decimal(int(rng.choice(MONTOS_CAJERO))).quantize(CENTIMOS)
    else:
        ciudad = cliente.ciudad_residencia
        if rng.random() < PROBABILIDAD_POS_FUERA:
            ciudad = str(rng.choice([c for c in indice.ciudades if c != ciudad]))
        mccs = [m for m in cliente.mccs_habituales if (ciudad, m) in indice.fisicos]
        comercio = _elegir(rng, indice.fisicos[(ciudad, str(rng.choice(mccs)))])
        dispositivo = None
        monto = _monto_compra(cliente, rng)

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


def _monto_compra(cliente: Cliente, rng: np.random.Generator) -> Decimal:
    """Monto con distribución log-normal de media y desviación del cliente."""
    media = float(cliente.gasto_media)
    desviacion = float(cliente.gasto_desviacion)
    sigma2 = np.log(1 + (desviacion / media) ** 2)
    valor = rng.lognormal(np.log(media) - sigma2 / 2, np.sqrt(sigma2))
    return max(MONTO_MINIMO, Decimal(repr(float(valor))).quantize(CENTIMOS))
