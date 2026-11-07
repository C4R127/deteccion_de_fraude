"""Pruebas del cliente de la API: token OAuth, envío por el gateway, reintentos (Tarea 2.4)."""

import asyncio
import json
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs

import httpx
import numpy as np
import pytest
import respx

from generador.__main__ import main
from generador.cliente_api import ProveedorToken, enviar_transacciones, leer_secreto
from generador.clientes import crear_poblacion
from generador.config import Config
from generador.normal import generar_normal

URL_TOKEN = "http://localhost:8180/realms/fraude/protocol/openid-connect/token"
URL_TX = "http://localhost:8080/api/v1/transacciones"
URL_SIEMBRA = "http://localhost:8081/internal/v1/siembra"
CAMPOS_SECCION_11 = {
    "idExterno", "tarjetaToken", "comercioId", "dispositivoHuella", "monto", "moneda",
    "canal", "pais", "latitud", "longitud", "fecha",
}


@pytest.fixture(autouse=True)
def secreto(monkeypatch):
    monkeypatch.setenv("GENERADOR_CLIENT_SECRET", "secreto-de-prueba")


def _token(valor="tok-1", expira=300):
    return httpx.Response(200, json={"access_token": valor, "expires_in": expira, "token_type": "Bearer"})


def _transacciones(n):
    config = Config(clientes=10, semilla=3)
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)
    desde = datetime(2026, 10, 1, tzinfo=timezone.utc)
    return list(generar_normal(poblacion, desde, desde + timedelta(days=3), rng))[:n]


def _respuesta_ok(request):
    return httpx.Response(201, json={"transaccionId": "x", "decision": "APROBAR", "score": 0,
                                     "respaldo": False})


async def _enviar(txs, **kwargs):
    config = Config()
    async with httpx.AsyncClient() as http:
        proveedor = ProveedorToken(config.oauth, http)
        return await enviar_transacciones(txs, config.url_gateway, http, proveedor, **kwargs)


def test_leer_secreto_desde_variable_de_entorno():
    assert leer_secreto("GENERADOR_CLIENT_SECRET") == "secreto-de-prueba"


def test_leer_secreto_falla_con_mensaje_claro(monkeypatch, tmp_path):
    monkeypatch.delenv("GENERADOR_CLIENT_SECRET")
    with pytest.raises(RuntimeError, match="GENERADOR_CLIENT_SECRET"):
        leer_secreto("GENERADOR_CLIENT_SECRET", archivo_env=tmp_path / "no-existe.env")


def test_leer_secreto_desde_archivo_env(monkeypatch, tmp_path):
    monkeypatch.delenv("GENERADOR_CLIENT_SECRET")
    archivo = tmp_path / ".env"
    archivo.write_text("# comentario\nOTRA=1\nGENERADOR_CLIENT_SECRET=desde-archivo\n", encoding="utf-8")

    assert leer_secreto("GENERADOR_CLIENT_SECRET", archivo_env=archivo) == "desde-archivo"


@respx.mock
def test_obtiene_token_con_client_credentials_y_lo_reutiliza():
    ruta_token = respx.post(URL_TOKEN).mock(return_value=_token())
    ruta_tx = respx.post(URL_TX).mock(side_effect=_respuesta_ok)

    resumen = asyncio.run(_enviar(_transacciones(5)))

    assert ruta_token.call_count == 1
    form = parse_qs(ruta_token.calls.last.request.content.decode())
    assert form == {"grant_type": ["client_credentials"], "client_id": ["generador"],
                    "client_secret": ["secreto-de-prueba"]}
    assert ruta_tx.call_count == 5
    for llamada in ruta_tx.calls:
        assert llamada.request.headers["Authorization"] == "Bearer tok-1"
        assert set(json.loads(llamada.request.content)) == CAMPOS_SECCION_11
    assert resumen.enviadas == 5
    assert resumen.decisiones["APROBAR"] == 5
    assert resumen.errores == 0


@respx.mock
def test_reintenta_cuando_el_gateway_responde_429():
    respx.post(URL_TOKEN).mock(return_value=_token())
    ruta_tx = respx.post(URL_TX).mock(side_effect=[
        httpx.Response(429), httpx.Response(429),
        httpx.Response(201, json={"transaccionId": "x", "decision": "REVISAR", "score": 55, "respaldo": False}),
    ])

    resumen = asyncio.run(_enviar(_transacciones(1), espera_base=0.001))

    assert ruta_tx.call_count == 3
    assert resumen.decisiones["REVISAR"] == 1
    assert resumen.reintentos == 2
    assert resumen.errores == 0


@respx.mock
def test_renueva_el_token_si_el_gateway_responde_401():
    ruta_token = respx.post(URL_TOKEN).mock(side_effect=[_token("viejo"), _token("nuevo")])
    ruta_tx = respx.post(URL_TX).mock(side_effect=[httpx.Response(401), _respuesta_ok(None)])

    resumen = asyncio.run(_enviar(_transacciones(1)))

    assert ruta_token.call_count == 2
    assert ruta_tx.calls[1].request.headers["Authorization"] == "Bearer nuevo"
    assert resumen.decisiones["APROBAR"] == 1


@respx.mock
def test_cuenta_errores_sin_detener_el_envio():
    respx.post(URL_TOKEN).mock(return_value=_token())
    respx.post(URL_TX).mock(side_effect=[httpx.Response(422, json={"title": "Validación fallida"}),
                                         _respuesta_ok(None), _respuesta_ok(None)])

    resumen = asyncio.run(_enviar(_transacciones(3), concurrencia=1))

    assert resumen.enviadas == 3
    assert resumen.errores == 1
    assert resumen.decisiones["APROBAR"] == 2


@respx.mock
def test_cuenta_las_decisiones_de_respaldo():
    respx.post(URL_TOKEN).mock(return_value=_token())
    respx.post(URL_TX).mock(return_value=httpx.Response(
        201, json={"transaccionId": "x", "decision": "APROBAR", "score": None, "respaldo": True}))

    resumen = asyncio.run(_enviar(_transacciones(2)))

    assert resumen.respaldo == 2


@respx.mock
def test_comando_siembra_usa_token_de_sistema():
    respx.post(URL_TOKEN).mock(return_value=_token("tok-siembra"))
    ruta = respx.post(URL_SIEMBRA).mock(return_value=httpx.Response(
        200, json={"clientes": 5, "tarjetas": 5, "comercios": 1, "dispositivos": 7}))

    assert main(["siembra", "--clientes", "5"]) == 0
    assert ruta.calls.last.request.headers["Authorization"] == "Bearer tok-siembra"


@respx.mock
def test_comando_historia_envia_transacciones_con_fechas_pasadas(capsys):
    respx.post(URL_TOKEN).mock(return_value=_token())
    ruta = respx.post(URL_TX).mock(side_effect=_respuesta_ok)

    assert main(["historia", "--dias", "1", "--clientes", "10"]) == 0

    assert ruta.call_count > 0
    ahora = datetime.now(timezone.utc)
    for llamada in ruta.calls:
        fecha = datetime.fromisoformat(json.loads(llamada.request.content)["fecha"])
        assert ahora - timedelta(days=1, minutes=1) <= fecha <= ahora
    assert "enviadas=" in capsys.readouterr().out


@respx.mock
def test_comando_vivo_envia_a_la_tasa_indicada_con_fecha_actual():
    respx.post(URL_TOKEN).mock(return_value=_token())
    ruta = respx.post(URL_TX).mock(side_effect=_respuesta_ok)

    inicio = datetime.now(timezone.utc)
    assert main(["vivo", "--tps", "10", "--duracion", "1", "--clientes", "10"]) == 0

    assert 8 <= ruta.call_count <= 12
    for llamada in ruta.calls:
        fecha = datetime.fromisoformat(json.loads(llamada.request.content)["fecha"])
        assert inicio - timedelta(seconds=1) <= fecha <= datetime.now(timezone.utc)


@respx.mock
def test_las_repetidas_se_cuentan_aparte_y_no_como_decisiones():
    respx.post(URL_TOKEN).mock(return_value=_token())
    respx.post(URL_TX).mock(side_effect=[
        _respuesta_ok(None),
        httpx.Response(200, json={"transaccionId": "x", "decision": "APROBAR", "score": 0, "respaldo": False}),
    ])

    resumen = asyncio.run(_enviar(_transacciones(2), concurrencia=1))

    assert resumen.enviadas == 2
    assert resumen.repetidas == 1
    assert resumen.decisiones["APROBAR"] == 1
    assert "repetidas=1" in resumen.texto()
