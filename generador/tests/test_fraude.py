"""Pruebas de los escenarios de fraude y de su mezcla con el tráfico en vivo (Tarea 4.6, ADR 0015)."""

import json
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

import httpx
import numpy as np
import pytest
import respx

from generador.__main__ import main
from generador.clientes import crear_poblacion
from generador.comercios import MCC_RIESGO
from generador.config import ESCENARIOS, Config
from generador.fraude import (
    EscritorVerdad,
    MezcladorVivo,
    escenarios_elegibles,
    generar_escenario,
    haversine_km,
)
from generador.ids import GeneradorIds
from generador.normal import IndiceComercios

LIMA = ZoneInfo("America/Lima")
# Mediodía y madrugada en Lima (UTC-5).
DIA = datetime(2026, 10, 5, 17, 0, tzinfo=timezone.utc)
NOCHE = datetime(2026, 10, 5, 7, 30, tzinfo=timezone.utc)
URL_TOKEN = "http://localhost:8180/realms/fraude/protocol/openid-connect/token"
URL_TX = "http://localhost:8080/api/v1/transacciones"


@pytest.fixture(scope="module")
def entorno():
    config = Config(clientes=80, semilla=7)
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    return config, poblacion, IndiceComercios(poblacion.comercios)


def _escenario(entorno, escenario, inicio=DIA, n=40):
    """``n`` corridas del escenario sobre clientes distintos."""
    config, poblacion, indice = entorno
    rng = np.random.default_rng(11)
    ids = GeneradorIds(config.semilla, "prueba", inicio)
    tarjeta_de = {t.cliente_id: t for t in poblacion.tarjetas}
    corridas = []
    for i in range(n):
        cliente = poblacion.clientes[i % len(poblacion.clientes)]
        corridas.append((cliente, generar_escenario(escenario, cliente, tarjeta_de[cliente.id], inicio,
                                                    indice, rng, ids)))
    return corridas


def _velocidad_kmh(a, b):
    horas = (b.fecha - a.fecha).total_seconds() / 3600
    return haversine_km(float(a.latitud), float(a.longitud), float(b.latitud), float(b.longitud)) / horas


def _comercio(entorno, comercio_id):
    _, poblacion, _ = entorno
    return next(c for c in poblacion.comercios if c.id == comercio_id)


def test_tarjeta_clonada_compra_lejana_minutos_despues_de_una_local(entorno):
    for cliente, planes in _escenario(entorno, "TARJETA_CLONADA"):
        local, lejana = planes
        assert local.escenario == lejana.escenario == "TARJETA_CLONADA"
        assert not local.fraude and lejana.fraude
        assert local.tx.canal == lejana.tx.canal == "POS"
        assert _comercio(entorno, local.tx.comercioId).ciudad == cliente.ciudad_residencia
        assert _comercio(entorno, lejana.tx.comercioId).ciudad != cliente.ciudad_residencia
        assert timedelta(minutes=2) <= lejana.tx.fecha - local.tx.fecha <= timedelta(minutes=10)
        # R03: más de 800 km/h.
        assert _velocidad_kmh(local.tx, lejana.tx) > 800
        assert local.tx.tarjetaToken == lejana.tx.tarjetaToken


def test_card_testing_compras_pequenas_en_linea_en_menos_de_diez_minutos(entorno):
    for cliente, planes in _escenario(entorno, "CARD_TESTING"):
        assert 6 <= len(planes) <= 12
        assert all(p.escenario == "CARD_TESTING" and p.fraude for p in planes)
        assert all(p.tx.canal == "ECOMMERCE" for p in planes)
        assert all(Decimal("1.00") <= p.tx.monto <= Decimal("10.00") for p in planes)
        assert planes[-1].tx.fecha - planes[0].tx.fecha < timedelta(minutes=10)
        assert [p.tx.fecha for p in planes] == sorted(p.tx.fecha for p in planes)
        # Huella nueva en cada intento (ADR 0015): ninguna conocida, ninguna repetida.
        huellas = [p.tx.dispositivoHuella for p in planes]
        assert len(set(huellas)) == len(huellas)
        assert not set(huellas) & set(cliente.dispositivos)


def test_toma_de_cuenta_dispositivo_nuevo_y_monto_alto(entorno):
    for cliente, planes in _escenario(entorno, "TOMA_DE_CUENTA"):
        (plan,) = planes
        assert plan.escenario == "TOMA_DE_CUENTA" and plan.fraude
        assert plan.tx.canal == "ECOMMERCE"
        assert plan.tx.dispositivoHuella not in cliente.dispositivos
        assert plan.tx.monto > 5 * cliente.gasto_media
        assert _comercio(entorno, plan.tx.comercioId).mcc not in MCC_RIESGO


def test_compra_atipica_monto_alto_en_rubro_de_riesgo(entorno):
    for cliente, planes in _escenario(entorno, "COMPRA_ATIPICA"):
        (plan,) = planes
        assert plan.escenario == "COMPRA_ATIPICA" and plan.fraude
        assert _comercio(entorno, plan.tx.comercioId).mcc in MCC_RIESGO
        assert _comercio(entorno, plan.tx.comercioId).mcc not in cliente.mccs_habituales
        assert plan.tx.monto > 5 * cliente.gasto_media
        assert plan.tx.dispositivoHuella in cliente.dispositivos


def test_uso_nocturno_varias_operaciones_de_madrugada(entorno):
    tamanos = Counter()
    for cliente, planes in _escenario(entorno, "USO_NOCTURNO", inicio=NOCHE):
        tamanos[len(planes)] += 1
        assert 1 <= len(planes) <= 4
        assert all(p.escenario == "USO_NOCTURNO" and p.fraude for p in planes)
        assert all(2 <= p.tx.fecha.astimezone(LIMA).hour <= 4 for p in planes)
        assert all(p.tx.canal == "ECOMMERCE" for p in planes)
        # R05 + R06 acompañan a R07 (ADR 0015): un mismo dispositivo nuevo y rubro de riesgo.
        assert all(_comercio(entorno, p.tx.comercioId).mcc in MCC_RIESGO for p in planes)
        assert len({p.tx.dispositivoHuella for p in planes}) == 1
        assert planes[0].tx.dispositivoHuella not in cliente.dispositivos
    assert sum(n for t, n in tamanos.items() if t >= 2) > 30


def test_uso_nocturno_no_pasa_de_las_cinco(entorno):
    tarde = datetime(2026, 10, 5, 9, 55, tzinfo=timezone.utc)  # 04:55 en Lima
    for _, planes in _escenario(entorno, "USO_NOCTURNO", inicio=tarde):
        assert all(p.tx.fecha.astimezone(LIMA).hour <= 4 for p in planes)


def test_uso_nocturno_solo_es_elegible_de_madrugada():
    pesos = {e: 1.0 for e in ESCENARIOS}
    assert "USO_NOCTURNO" not in escenarios_elegibles(pesos, DIA)
    assert "USO_NOCTURNO" in escenarios_elegibles(pesos, NOCHE)
    assert set(escenarios_elegibles(pesos, DIA)) == set(ESCENARIOS) - {"USO_NOCTURNO"}


def _mezcla(config, poblacion, slots, inicio=DIA, paso=timedelta(seconds=2)):
    rng = np.random.default_rng(config.semilla)
    mezclador = MezcladorVivo(poblacion, config, rng, GeneradorIds(config.semilla, "vivo", inicio))
    salida = []
    for i in range(slots):
        plan = mezclador.siguiente(inicio + i * paso)
        if plan is not None:
            salida.append(plan)
    return salida


@pytest.fixture(scope="module")
def mezcla_larga():
    """~22 horas de tráfico a 0,5 tps con 500 clientes (incluye una madrugada)."""
    config = Config(clientes=500, semilla=42)
    poblacion = crear_poblacion(config, np.random.default_rng(config.semilla))
    return config, poblacion, _mezcla(config, poblacion, 40_000)


def test_la_proporcion_de_fraude_se_respeta_con_tolerancia(mezcla_larga):
    config, _, salida = mezcla_larga
    fraudes = sum(1 for p in salida if p.fraude)
    proporcion = fraudes / len(salida)
    assert abs(proporcion - config.proporcion_fraude) <= 0.2 * config.proporcion_fraude
    assert len(salida) >= 0.99 * 40_000
    por_escenario = Counter(p.escenario for p in salida if p.fraude)
    assert set(por_escenario) == set(ESCENARIOS)


def test_uso_nocturno_solo_aparece_de_madrugada(mezcla_larga):
    _, _, salida = mezcla_larga
    for p in salida:
        if p.escenario == "USO_NOCTURNO":
            assert 2 <= p.tx.fecha.astimezone(LIMA).hour <= 4


def test_trafico_normal_espaciado_por_tarjeta(mezcla_larga):
    """ADR 0015: al menos 15 minutos entre transacciones normales de una tarjeta (R02 no salta)."""
    _, _, salida = mezcla_larga
    ultima = {}
    for p in salida:
        if p.escenario is None:
            anterior = ultima.get(p.tx.tarjetaToken)
            if anterior is not None and anterior[1] is None:
                assert p.tx.fecha - anterior[0] >= timedelta(minutes=15)
        ultima[p.tx.tarjetaToken] = (p.tx.fecha, p.escenario)


def test_trafico_normal_sin_viajes_imposibles(mezcla_larga):
    """ADR 0015: una compra normal fuera de la ciudad no implica más de 800 km/h (R03 no salta)."""
    _, _, salida = mezcla_larga
    fisicas = defaultdict(list)
    for p in salida:
        if p.tx.latitud is not None:
            fisicas[p.tx.tarjetaToken].append(p)
    revisadas = 0
    for lista in fisicas.values():
        for anterior, actual in zip(lista, lista[1:]):
            if actual.escenario is None and anterior.escenario is None and anterior.tx.comercioId != actual.tx.comercioId:
                revisadas += 1
                assert _velocidad_kmh(anterior.tx, actual.tx) <= 800
    assert revisadas > 1000


def test_misma_semilla_misma_mezcla():
    config = Config(clientes=60, semilla=5, proporcion_fraude=0.2)
    poblacion = crear_poblacion(config, np.random.default_rng(config.semilla))
    a = [(p.tx.a_json(), p.escenario, p.fraude) for p in _mezcla(config, poblacion, 500)]
    b = [(p.tx.a_json(), p.escenario, p.fraude) for p in _mezcla(config, poblacion, 500)]
    assert a == b
    assert any(f for _, _, f in a)


def test_escritor_de_verdad_escribe_una_linea_por_fraude(tmp_path):
    ruta = tmp_path / "verdad.jsonl"
    with EscritorVerdad(ruta) as verdad:
        verdad.registrar("6f1c2a2e-0000-4000-8000-000000000001", "CARD_TESTING")
    with EscritorVerdad(ruta) as verdad:
        verdad.registrar("6f1c2a2e-0000-4000-8000-000000000002", "TOMA_DE_CUENTA")
    lineas = [json.loads(linea) for linea in ruta.read_text(encoding="utf-8").splitlines()]
    assert lineas == [
        {"idExterno": "6f1c2a2e-0000-4000-8000-000000000001", "escenario": "CARD_TESTING"},
        {"idExterno": "6f1c2a2e-0000-4000-8000-000000000002", "escenario": "TOMA_DE_CUENTA"},
    ]


def _respuesta(request):
    return httpx.Response(201, json={"transaccionId": "x", "decision": "APROBAR", "score": 0, "respaldo": False})


@respx.mock
def test_vivo_registra_en_verdad_cada_fraude_enviado(tmp_path):
    config = tmp_path / "config.yaml"
    config.write_text("clientes: 60\nsemilla: 9\nproporcion_fraude: 0.3\nespaciado_minutos: 0\n",
                      encoding="utf-8")
    verdad = tmp_path / "verdad.jsonl"
    respx.post(URL_TOKEN).mock(return_value=httpx.Response(
        200, json={"access_token": "t", "expires_in": 300, "token_type": "Bearer"}))
    ruta = respx.post(URL_TX).mock(side_effect=_respuesta)

    assert main(["--config", str(config), "vivo", "--tps", "200", "--duracion", "1.5",
                 "--verdad", str(verdad)]) == 0

    enviadas = {json.loads(c.request.content)["idExterno"]: json.loads(c.request.content) for c in ruta.calls}
    lineas = [json.loads(linea) for linea in verdad.read_text(encoding="utf-8").splitlines()]
    assert lineas, "debió haber fraudes con proporción 0,3"
    assert {linea["idExterno"] for linea in lineas} <= set(enviadas)
    assert all(linea["escenario"] in ESCENARIOS for linea in lineas)
    for linea in lineas:
        if linea["escenario"] == "CARD_TESTING":
            assert enviadas[linea["idExterno"]]["monto"] <= 10
    # Las normales no van a la verdad: con 0,3 de fraude, buena parte de lo enviado es normal.
    assert len(lineas) < len(enviadas)


@respx.mock
def test_vivo_no_registra_en_verdad_lo_que_la_api_rechaza(tmp_path):
    config = tmp_path / "config.yaml"
    config.write_text("clientes: 30\nsemilla: 9\nproporcion_fraude: 1.0\nespaciado_minutos: 0\n",
                      encoding="utf-8")
    verdad = tmp_path / "verdad.jsonl"
    respx.post(URL_TOKEN).mock(return_value=httpx.Response(
        200, json={"access_token": "t", "expires_in": 300, "token_type": "Bearer"}))
    ruta = respx.post(URL_TX).mock(return_value=httpx.Response(422, json={"title": "invalida"}))

    assert main(["--config", str(config), "vivo", "--tps", "50", "--duracion", "0.5",
                 "--verdad", str(verdad)]) == 0

    assert ruta.call_count > 0
    assert not verdad.exists() or verdad.read_text(encoding="utf-8") == ""
