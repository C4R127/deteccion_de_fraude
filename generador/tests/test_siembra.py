"""Pruebas del comando siembra (Tarea 2.1)."""

import json

import httpx
import numpy as np
import respx

from generador.__main__ import main
from generador.clientes import crear_poblacion
from generador.config import Config
from generador.siembra import payload_siembra

URL = "http://localhost:8081/internal/v1/siembra"


def _poblacion():
    config = Config(clientes=10, semilla=7)
    return crear_poblacion(config, np.random.default_rng(config.semilla))


def test_payload_contiene_toda_la_poblacion_con_nombres_de_la_api():
    poblacion = _poblacion()

    payload = payload_siembra(poblacion)

    assert len(payload["clientes"]) == 10
    assert len(payload["tarjetas"]) == 10
    assert len(payload["comercios"]) == len(poblacion.comercios)
    assert len(payload["dispositivos"]) == sum(len(c.dispositivos) for c in poblacion.clientes)
    assert set(payload["clientes"][0]) == {"id", "nombre", "segmento", "ciudadResidencia", "fechaAlta"}
    assert set(payload["tarjetas"][0]) == {"id", "clienteId", "token", "ultimos4", "estado", "fechaEmision"}
    assert set(payload["comercios"][0]) == {"id", "nombre", "mcc", "pais", "ciudad", "latitud",
                                            "longitud", "online"}
    assert set(payload["dispositivos"][0]) == {"huella", "clienteId"}


def test_payload_es_json_serializable_con_fechas_utc():
    payload = payload_siembra(_poblacion())

    texto = json.dumps(payload)

    assert payload["clientes"][0]["fechaAlta"].endswith("Z")
    assert "tok_" in texto


@respx.mock
def test_comando_siembra_envia_la_poblacion_al_endpoint_interno(capsys):
    ruta = respx.post(URL).mock(return_value=httpx.Response(
        200, json={"clientes": 20, "tarjetas": 20, "comercios": 1, "dispositivos": 30}))

    codigo = main(["siembra", "--clientes", "20"])

    assert codigo == 0
    assert ruta.call_count == 1
    enviado = json.loads(ruta.calls.last.request.content)
    assert len(enviado["clientes"]) == 20
    assert "clientes=20" in capsys.readouterr().out


@respx.mock
def test_comando_siembra_falla_si_la_api_responde_error(capsys):
    respx.post(URL).mock(return_value=httpx.Response(422, json={"title": "Validación fallida"}))

    codigo = main(["siembra", "--clientes", "5"])

    assert codigo == 1
    assert "422" in capsys.readouterr().err
