"""CLI del generador.

- ``seco --dias N``: imprime transacciones normales en JSONL sin llamar a la API.
- ``siembra``: crea clientes, tarjetas, comercios y dispositivos (endpoint interno).
- ``historia --dias N``: envía N días de transacciones normales con fechas pasadas.
- ``vivo --tps T``: envía transacciones con fecha actual a T por segundo, mezclando los
  escenarios de fraude (§6.3); cada fraude registrado se agrega a ``verdad.jsonl``.
"""

import argparse
import asyncio
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import numpy as np

from generador.cliente_api import ProveedorToken, Resumen, enviar_transacciones, enviar_una
from generador.clientes import crear_poblacion
from generador.config import Config, cargar_config
from generador.fraude import EscritorVerdad, MezcladorVivo, Planificada
from generador.ids import GeneradorIds
from generador.modelos import Poblacion
from generador.normal import generar_normal
from generador.siembra import enviar_siembra

CONFIG_POR_DEFECTO = Path(__file__).resolve().parents[1] / "config.yaml"
VERDAD_POR_DEFECTO = Path(__file__).resolve().parents[1] / "verdad.jsonl"
TIEMPO_LIMITE_HTTP = 60.0


def _argumentos(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python -m generador")
    parser.add_argument("--config", type=Path, default=CONFIG_POR_DEFECTO,
                        help="ruta de config.yaml")
    sub = parser.add_subparsers(dest="comando", required=True)

    def con_clientes(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
        p.add_argument("--clientes", type=int, help="sobrescribe la cantidad de clientes")
        return p

    seco = con_clientes(sub.add_parser("seco", help="imprime JSONL sin llamar a la API"))
    seco.add_argument("--dias", type=int, required=True, help="días hacia atrás desde hoy")

    con_clientes(sub.add_parser("siembra", help="crea clientes, tarjetas, comercios y dispositivos"))

    historia = con_clientes(sub.add_parser("historia", help="envía N días de transacciones pasadas"))
    historia.add_argument("--dias", type=int, required=True, help="días hacia atrás desde ahora")
    historia.add_argument("--concurrencia", type=int, default=20, help="solicitudes en vuelo")

    vivo = con_clientes(sub.add_parser("vivo", help="envía transacciones con fecha actual"))
    vivo.add_argument("--tps", type=float, help="transacciones por segundo (por defecto, la de config)")
    vivo.add_argument("--duracion", type=float, help="segundos de envío (por defecto, hasta Ctrl+C)")
    vivo.add_argument("--verdad", type=Path, default=VERDAD_POR_DEFECTO,
                      help="archivo JSONL donde se agregan los fraudes enviados")
    return parser.parse_args(argv)


async def _siembra(config: Config, poblacion: Poblacion) -> int:
    async with httpx.AsyncClient(timeout=TIEMPO_LIMITE_HTTP) as http:
        try:
            resumen = await enviar_siembra(poblacion, config.url_transacciones, http,
                                           ProveedorToken(config.oauth, http))
        except httpx.HTTPStatusError as error:
            print(f"siembra fallida: HTTP {error.response.status_code} {error.response.text}",
                  file=sys.stderr)
            return 1
        except (httpx.HTTPError, RuntimeError) as error:
            print(f"siembra fallida: {error}", file=sys.stderr)
            return 1
    print("siembra completa: " + ", ".join(f"{k}={v}" for k, v in resumen.items()))
    return 0


async def _historia(config: Config, poblacion: Poblacion, rng: np.random.Generator, dias: int,
                    concurrencia: int) -> int:
    hasta = datetime.now(timezone.utc).replace(microsecond=0)
    desde = hasta - timedelta(days=dias)
    async with httpx.AsyncClient(timeout=TIEMPO_LIMITE_HTTP) as http:
        ids = GeneradorIds(config.semilla, "historia", desde)
        resumen = await enviar_transacciones(generar_normal(poblacion, desde, hasta, rng, ids),
                                             config.url_gateway, http, ProveedorToken(config.oauth, http),
                                             concurrencia=concurrencia)
    print(f"historia completa: {resumen.texto()}")
    return 0 if resumen.errores == 0 else 1


async def _vivo(config: Config, poblacion: Poblacion, rng: np.random.Generator, tps: float,
                duracion: float | None, ruta_verdad: Path) -> int:
    mezclador = MezcladorVivo(poblacion, config, rng,
                              GeneradorIds(config.semilla, "vivo", datetime.now(timezone.utc)))
    resumen = Resumen()
    fraudes = 0
    # Turnos sin cliente disponible: tps mayor de lo que admite el espaciado (ADR 0015).
    omitidas = 0
    intervalo = 1.0 / tps
    tareas: set[asyncio.Task] = set()

    async def enviar(plan: Planificada) -> None:
        nonlocal fraudes
        registrada = await enviar_una(plan.tx, config.url_gateway, http, proveedor, resumen)
        # Solo lo que la API registró: la verdad no debe nombrar transacciones inexistentes.
        if registrada and plan.fraude:
            verdad.registrar(plan.tx.idExterno, plan.escenario)
            fraudes += 1

    with EscritorVerdad(ruta_verdad) as verdad:
        async with httpx.AsyncClient(timeout=TIEMPO_LIMITE_HTTP) as http:
            proveedor = ProveedorToken(config.oauth, http)
            # Después de crear el cliente HTTP, que en Windows tarda cientos de ms.
            inicio = time.monotonic()
            turnos = 0
            while duracion is None or time.monotonic() - inicio < duracion:
                # Ritmo fijo: el turno n sale en inicio + n / tps.
                espera = inicio + turnos * intervalo - time.monotonic()
                if espera > 0:
                    await asyncio.sleep(espera)
                if duracion is not None and time.monotonic() - inicio >= duracion:
                    break
                turnos += 1
                plan = mezclador.siguiente(datetime.now(timezone.utc))
                if plan is None:
                    omitidas += 1
                    continue
                tarea = asyncio.create_task(enviar(plan))
                tareas.add(tarea)
                tarea.add_done_callback(tareas.discard)
            if tareas:
                await asyncio.gather(*tareas)
    print(f"vivo terminado: {resumen.texto()} fraudes={fraudes} omitidas={omitidas}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _argumentos(argv)
    config = cargar_config(args.config)
    if args.clientes is not None:
        config = config.model_copy(update={"clientes": args.clientes})
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)

    if args.comando == "seco":
        hasta = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        for tx in generar_normal(poblacion, hasta - timedelta(days=args.dias), hasta, rng):
            sys.stdout.write(tx.a_json() + "\n")
        return 0
    if args.comando == "siembra":
        return asyncio.run(_siembra(config, poblacion))
    if args.comando == "historia":
        return asyncio.run(_historia(config, poblacion, rng, args.dias, args.concurrencia))
    if args.comando == "vivo":
        try:
            return asyncio.run(_vivo(config, poblacion, rng, args.tps or config.tps, args.duracion,
                                     args.verdad))
        except KeyboardInterrupt:
            return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
