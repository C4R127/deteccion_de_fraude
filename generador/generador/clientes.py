"""Población ficticia: clientes con hábitos, sus tarjetas y los comercios (§6.1)."""

from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID

import numpy as np
from faker import Faker

from generador.comercios import CIUDADES, MCC_FISICOS, MCC_ONLINE, crear_comercios
from generador.config import SEGMENTOS, Config
from generador.modelos import Cliente, Poblacion, Tarjeta

# Faker no tiene locale es_PE; "es" da nombres en español neutros.
LOCALE_FAKER = "es"
# Fecha de referencia fija para que la población no dependa del reloj.
REFERENCIA = datetime(2026, 1, 1, tzinfo=timezone.utc)

# Gasto típico por segmento en PEN: (media, desviación) y transacciones por día.
GASTO_POR_SEGMENTO = {
    "ESTANDAR": (Decimal("120.00"), Decimal("60.00"), 1.2),
    "PREMIUM": (Decimal("450.00"), Decimal("250.00"), 1.8),
    "JOVEN": (Decimal("60.00"), Decimal("30.00"), 1.5),
}
# Franjas horarias habituales en hora de Lima (ninguna entre 02:00 y 04:59).
FRANJAS = (
    tuple(range(7, 10)),    # mañana
    tuple(range(12, 15)),   # almuerzo
    tuple(range(18, 22)),   # tarde-noche
    tuple(range(10, 13)),   # media mañana
    tuple(range(20, 24)),   # noche
)
# Peso de la residencia: Lima concentra más clientes.
PESO_CIUDAD = {"Lima": 0.45, "Arequipa": 0.12, "Trujillo": 0.11, "Cusco": 0.1,
               "Piura": 0.11, "Chiclayo": 0.11}


def uuid_determinista(rng: np.random.Generator) -> UUID:
    return UUID(bytes=rng.bytes(16), version=4)


def crear_poblacion(config: Config, rng: np.random.Generator) -> Poblacion:
    faker = Faker(LOCALE_FAKER)
    faker.seed_instance(int(rng.integers(0, 2**31)))

    comercios = crear_comercios(rng)
    clientes: list[Cliente] = []
    tarjetas: list[Tarjeta] = []
    segmentos = list(SEGMENTOS)
    pesos_segmento = [config.distribucion_segmentos[s] for s in segmentos]
    ciudades = [c.nombre for c in CIUDADES]
    pesos_ciudad = [PESO_CIUDAD[c] for c in ciudades]

    for _ in range(config.clientes):
        cliente = _crear_cliente(faker, rng, str(rng.choice(segmentos, p=pesos_segmento)),
                                 str(rng.choice(ciudades, p=pesos_ciudad)))
        clientes.append(cliente)
        tarjetas.append(_crear_tarjeta(rng, cliente))

    return Poblacion(clientes=tuple(clientes), tarjetas=tuple(tarjetas), comercios=comercios)


def _crear_cliente(faker: Faker, rng: np.random.Generator, segmento: str, ciudad: str) -> Cliente:
    media, desviacion, por_dia = GASTO_POR_SEGMENTO[segmento]
    factor = Decimal(str(round(float(rng.uniform(0.7, 1.3)), 2)))
    franjas = rng.choice(len(FRANJAS), size=2, replace=False)
    horas = tuple(sorted({h for i in franjas for h in FRANJAS[i]}))
    mccs = tuple(sorted(rng.choice(list(MCC_FISICOS), size=4, replace=False).tolist()))
    mccs_online = tuple(sorted(rng.choice(list(MCC_ONLINE), size=2, replace=False).tolist()))
    p_ecommerce = 0.35 if segmento == "JOVEN" else 0.2
    p_atm = 0.08
    canales = (("POS", round(1 - p_ecommerce - p_atm, 2)), ("ECOMMERCE", p_ecommerce),
               ("ATM", p_atm))
    dispositivos = tuple(f"dev_{rng.bytes(6).hex()}" for _ in range(int(rng.integers(1, 3))))
    return Cliente(
        id=uuid_determinista(rng),
        nombre=faker.name(),
        segmento=segmento,
        ciudad_residencia=ciudad,
        fecha_alta=REFERENCIA - timedelta(days=int(rng.integers(90, 3650))),
        gasto_media=(media * factor).quantize(Decimal("0.01")),
        gasto_desviacion=(desviacion * factor).quantize(Decimal("0.01")),
        transacciones_por_dia=por_dia,
        horas_habituales=horas,
        mccs_habituales=mccs + mccs_online,
        canales_habituales=canales,
        dispositivos=dispositivos,
    )


def _crear_tarjeta(rng: np.random.Generator, cliente: Cliente) -> Tarjeta:
    return Tarjeta(
        id=uuid_determinista(rng),
        cliente_id=cliente.id,
        token=f"tok_{rng.bytes(4).hex()}",
        ultimos4=f"{int(rng.integers(0, 10000)):04d}",
        fecha_emision=cliente.fecha_alta + timedelta(days=int(rng.integers(0, 30))),
    )
