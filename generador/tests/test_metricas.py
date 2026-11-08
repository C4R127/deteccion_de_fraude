"""Comando ``metricas``: sube verdad.jsonl a POST /api/v1/metricas/verdad (§6.2, ADR 0017)."""

import json

import httpx
import pytest
import respx

from generador.__main__ import main
from generador.metricas import leer_verdad

URL_TOKEN = "http://localhost:8180/realms/fraude/protocol/openid-connect/token"
URL_VERDAD = "http://localhost:8080/api/v1/metricas/verdad"


def _token():
    return httpx.Response(200, json={"access_token": "t", "expires_in": 300, "token_type": "Bearer"})


def _escribir(ruta, n, repetir_primera=False):
    lineas = [{"idExterno": f"00000000-0000-4000-8000-{i:012d}", "escenario": "CARD_TESTING"} for i in range(n)]
    if repetir_primera:
        lineas.append(lineas[0])
    ruta.write_text("".join(json.dumps(l) + "\n" for l in lineas) + "\n", encoding="utf-8")


def test_leer_verdad_ignora_lineas_vacias_y_repetidas(tmp_path):
    ruta = tmp_path / "verdad.jsonl"
    _escribir(ruta, 3, repetir_primera=True)

    etiquetas = leer_verdad(ruta)

    assert len(etiquetas) == 3
    assert etiquetas[0] == {"idExterno": "00000000-0000-4000-8000-000000000000", "escenario": "CARD_TESTING"}


def test_leer_verdad_rechaza_lineas_invalidas(tmp_path):
    ruta = tmp_path / "verdad.jsonl"
    ruta.write_text('{"idExterno": "x"}\n', encoding="utf-8")

    with pytest.raises(ValueError, match="línea 1"):
        leer_verdad(ruta)


@respx.mock
def test_comando_metricas_sube_por_lotes_con_token(tmp_path):
    ruta = tmp_path / "verdad.jsonl"
    _escribir(ruta, 2500)
    respx.post(URL_TOKEN).mock(return_value=_token())
    subida = respx.post(URL_VERDAD).mock(side_effect=lambda r: httpx.Response(
        200, json={"recibidas": len(json.loads(r.content)), "nuevas": len(json.loads(r.content))}))

    assert main(["metricas", "--verdad", str(ruta)]) == 0

    lotes = [json.loads(c.request.content) for c in subida.calls]
    assert [len(l) for l in lotes] == [1000, 1000, 500]
    assert all(c.request.headers["Authorization"] == "Bearer t" for c in subida.calls)
    assert lotes[2][-1]["idExterno"] == "00000000-0000-4000-8000-000000002499"


@respx.mock
def test_comando_metricas_falla_si_la_api_rechaza(tmp_path, capsys):
    ruta = tmp_path / "verdad.jsonl"
    _escribir(ruta, 3)
    respx.post(URL_TOKEN).mock(return_value=_token())
    respx.post(URL_VERDAD).mock(return_value=httpx.Response(403, json={"status": 403}))

    assert main(["metricas", "--verdad", str(ruta)]) == 1
    assert "403" in capsys.readouterr().err


def test_comando_metricas_sin_archivo(tmp_path, capsys):
    assert main(["metricas", "--verdad", str(tmp_path / "no-existe.jsonl")]) == 1
    assert "no existe" in capsys.readouterr().err
