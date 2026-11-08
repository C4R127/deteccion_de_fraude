"""Pruebas de la población y las transacciones normales (Tarea 1.4)."""

import json
import re
from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

import numpy as np
import pytest
from pydantic import ValidationError

from generador.__main__ import main
from generador.clientes import crear_poblacion
from generador.config import Config, cargar_config
from generador.normal import generar_normal

DESDE = datetime(2026, 10, 1, tzinfo=timezone.utc)
HASTA = DESDE + timedelta(days=7)
CAMPOS_SECCION_11 = {
    "idExterno", "tarjetaToken", "comercioId", "dispositivoHuella", "monto", "moneda",
    "canal", "pais", "latitud", "longitud", "fecha",
}


def _config(**cambios) -> Config:
    base = {"clientes": 60, "semilla": 42}
    base.update(cambios)
    return Config(**base)


def _corrida(semilla: int = 42) -> list[str]:
    config = _config(semilla=semilla)
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    return [t.a_json() for t in generar_normal(poblacion, DESDE, HASTA, rng)]


@pytest.fixture(scope="module")
def poblacion_y_transacciones():
    config = _config()
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    transacciones = list(generar_normal(poblacion, DESDE, HASTA, rng))
    return poblacion, transacciones


def test_misma_semilla_misma_salida():
    assert _corrida(42) == _corrida(42)


def test_distinta_semilla_distinta_salida():
    assert _corrida(42) != _corrida(43)


def test_poblacion_tiene_la_cantidad_configurada(poblacion_y_transacciones):
    poblacion, _ = poblacion_y_transacciones
    assert len(poblacion.clientes) == 60
    assert all(1 <= len(c.dispositivos) <= 2 for c in poblacion.clientes)
    assert {c.ciudad_residencia for c in poblacion.clientes} <= {
        "Lima", "Arequipa", "Trujillo", "Cusco", "Piura", "Chiclayo"}


def test_genera_transacciones(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    assert len(transacciones) > 100


def test_salida_tiene_exactamente_los_campos_de_la_seccion_11(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    for t in transacciones[:50]:
        assert set(json.loads(t.a_json())) == CAMPOS_SECCION_11


def test_monto_positivo_con_dos_decimales(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    for t in transacciones:
        assert isinstance(t.monto, Decimal)
        assert t.monto > 0
        assert t.monto.as_tuple().exponent == -2


def test_monto_en_json_es_numero(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    assert isinstance(json.loads(transacciones[0].a_json())["monto"], (int, float))


def test_ecommerce_sin_coordenadas_y_pos_atm_con_coordenadas(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    canales = Counter(t.canal for t in transacciones)
    assert canales["POS"] > 0 and canales["ECOMMERCE"] > 0 and canales["ATM"] > 0
    for t in transacciones:
        if t.canal == "ECOMMERCE":
            assert t.latitud is None and t.longitud is None
        else:
            assert t.latitud is not None and t.longitud is not None


def test_valores_fijos_del_mvp(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    assert {t.moneda for t in transacciones} == {"PEN"}
    assert {t.pais for t in transacciones} == {"PE"}
    assert {t.canal for t in transacciones} <= {"POS", "ECOMMERCE", "ATM"}


def test_mayoria_de_compras_pos_en_la_ciudad_de_residencia(poblacion_y_transacciones):
    poblacion, transacciones = poblacion_y_transacciones
    ciudad_de_cliente = {c.id: c.ciudad_residencia for c in poblacion.clientes}
    cliente_de_token = {t.token: t.cliente_id for t in poblacion.tarjetas}
    ciudad_de_comercio = {c.id: c.ciudad for c in poblacion.comercios}

    pos = [t for t in transacciones if t.canal == "POS"]
    locales = sum(
        1 for t in pos
        if ciudad_de_comercio[t.comercioId] == ciudad_de_cliente[cliente_de_token[t.tarjetaToken]]
    )
    assert locales / len(pos) >= 0.8


def test_tokens_no_parecen_numeros_de_tarjeta(poblacion_y_transacciones):
    poblacion, transacciones = poblacion_y_transacciones
    for tarjeta in poblacion.tarjetas:
        assert re.fullmatch(r"tok_[0-9a-f]{8}", tarjeta.token)
        assert re.fullmatch(r"\d{4}", tarjeta.ultimos4)
    for t in transacciones:
        assert t.tarjetaToken.startswith("tok_")
        assert not re.search(r"\d{13,19}", t.a_json())


def test_fechas_en_rango_utc_y_ordenadas(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    fechas = [t.fecha for t in transacciones]
    assert fechas == sorted(fechas)
    assert all(DESDE <= f < HASTA for f in fechas)
    assert all(f.utcoffset() == timedelta(0) for f in fechas)
    assert json.loads(transacciones[0].a_json())["fecha"].endswith("Z")


def test_id_externo_unico(poblacion_y_transacciones):
    _, transacciones = poblacion_y_transacciones
    assert len({t.idExterno for t in transacciones}) == len(transacciones)


def test_ecommerce_usa_un_dispositivo_del_cliente(poblacion_y_transacciones):
    poblacion, transacciones = poblacion_y_transacciones
    cliente_de_token = {t.token: t.cliente_id for t in poblacion.tarjetas}
    dispositivos = {c.id: set(c.dispositivos) for c in poblacion.clientes}
    for t in transacciones:
        if t.canal == "ECOMMERCE":
            assert t.dispositivoHuella in dispositivos[cliente_de_token[t.tarjetaToken]]


def test_config_rechaza_proporcion_de_fraude_invalida():
    with pytest.raises(ValidationError):
        _config(proporcion_fraude=1.5)


def test_config_rechaza_distribucion_que_no_suma_uno():
    with pytest.raises(ValidationError):
        _config(distribucion_segmentos={"ESTANDAR": 0.5, "PREMIUM": 0.2, "JOVEN": 0.2})


def test_config_yaml_del_repositorio_es_valido():
    config = cargar_config(Path(__file__).parents[1] / "config.yaml")
    assert config.clientes == 500
    assert config.proporcion_fraude == 0.02


def test_cli_seco_imprime_jsonl_sin_llamar_a_la_api(capsys):
    codigo = main(["seco", "--dias", "1", "--clientes", "20"])

    lineas = capsys.readouterr().out.strip().splitlines()
    assert codigo == 0
    assert len(lineas) > 0
    assert all(set(json.loads(linea)) == CAMPOS_SECCION_11 for linea in lineas)


def test_ids_reproducibles_para_la_misma_semilla_modo_e_inicio():
    from generador.ids import GeneradorIds

    a = GeneradorIds(42, "historia", DESDE)
    b = GeneradorIds(42, "historia", DESDE)

    assert [a.siguiente() for _ in range(5)] == [b.siguiente() for _ in range(5)]


def test_ids_distintos_entre_corridas_con_otro_inicio_o_modo():
    from generador.ids import GeneradorIds

    def ids(modo, inicio):
        g = GeneradorIds(42, modo, inicio)
        return {g.siguiente() for _ in range(2000)}

    historia_1 = ids("historia", DESDE)
    historia_30 = ids("historia", DESDE - timedelta(days=29))
    vivo = ids("vivo", DESDE)
    assert historia_1.isdisjoint(historia_30)
    assert historia_1.isdisjoint(vivo)


def test_dos_ventanas_distintas_no_repiten_id_externo():
    from generador.ids import GeneradorIds

    def corrida(desde):
        config = _config()
        rng = np.random.default_rng(config.semilla)
        poblacion = crear_poblacion(config, rng)
        ids = GeneradorIds(config.semilla, "historia", desde)
        return {t.idExterno for t in generar_normal(poblacion, desde, HASTA, rng, ids)}

    corta = corrida(HASTA - timedelta(days=1))
    larga = corrida(DESDE)
    assert corta and larga
    assert corta.isdisjoint(larga)


# --- Coherencia con las reglas en la historia (ADR 0015, actualización) ---

def _dispara_r03(a, b):
    """R03 (§7.2): bajo 60 s, más de 50 km; desde 60 s, más de 800 km/h."""
    from generador.geo import haversine_km
    segundos = (b.fecha - a.fecha).total_seconds()
    distancia = haversine_km(float(a.latitud), float(a.longitud), float(b.latitud), float(b.longitud))
    return distancia > 50 if segundos < 60 else distancia / (segundos / 3600) > 800


def test_historia_sin_viajes_imposibles():
    """R03 no debe saltar con tráfico normal: entre compras físicas de una tarjeta, viaje posible."""
    config = _config(clientes=200, semilla=42)
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    por_tarjeta = {}
    for t in generar_normal(poblacion, DESDE, DESDE + timedelta(days=30), rng):
        if t.latitud is not None:
            por_tarjeta.setdefault(t.tarjetaToken, []).append(t)
    revisadas = 0
    for lista in por_tarjeta.values():
        for anterior, actual in zip(lista, lista[1:]):
            revisadas += 1
            assert not _dispara_r03(anterior, actual), (anterior, actual)
    assert revisadas > 3000


def test_historia_sigue_teniendo_viajes_legitimos(poblacion_y_transacciones):
    """La regla de viaje no elimina las compras en otra ciudad: solo exige tiempo para llegar."""
    poblacion, transacciones = poblacion_y_transacciones
    ciudad_de_cliente = {c.id: c.ciudad_residencia for c in poblacion.clientes}
    cliente_de_token = {t.token: t.cliente_id for t in poblacion.tarjetas}
    ciudad_de_comercio = {c.id: c.ciudad for c in poblacion.comercios}
    fuera = [t for t in transacciones if t.canal == "POS"
             and ciudad_de_comercio[t.comercioId] != ciudad_de_cliente[cliente_de_token[t.tarjetaToken]]]
    assert fuera


def test_retiros_de_cajero_proporcionales_al_gasto():
    """R01 no debe saltar por un retiro normal: a lo sumo 3 veces el gasto medio (mínimo 50)."""
    config = _config(clientes=200, semilla=7)
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    gasto = {t.token: c.gasto_media for c in poblacion.clientes for t in poblacion.tarjetas if t.cliente_id == c.id}
    retiros = [t for t in generar_normal(poblacion, DESDE, HASTA, rng) if t.canal == "ATM"]
    assert len(retiros) > 50
    for t in retiros:
        assert t.monto <= max(Decimal("50"), 3 * gasto[t.tarjetaToken])
