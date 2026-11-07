"""CLI del generador.

- ``seco --dias N``: imprime transacciones normales en JSONL sin llamar a la API.
- ``siembra``: crea clientes, tarjetas, comercios y dispositivos (endpoint interno).
- ``historia --dias N``: envía N días de transacciones normales con fechas pasadas.
- ``vivo --tps T``: envía transacciones con fecha actual a T por segundo.
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
from generador.modelos import Poblacion
from generador.normal import GeneradorVivo, generar_normal
from generador.siembra import enviar_siembra

CONFIG_POR_DEFECTO = Path(__file__).resolve().parents[1] / "config.yaml"
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
        resumen = await enviar_transacciones(generar_normal(poblacion, desde, hasta, rng),
                                             config.url_gateway, http, ProveedorToken(config.oauth, http),
                                             concurrencia=concurrencia)
    print(f"historia completa: {resumen.texto()}")
    return 0 if resumen.errores == 0 else 1


async def _vivo(config: Config, poblacion: Poblacion, rng: np.random.Generator, tps: float,
                duracion: float | None) -> int:
    generador = GeneradorVivo(poblacion, rng)
    resumen = Resumen()
    intervalo = 1.0 / tps
    inicio = time.monotonic()
    tareas: set[asyncio.Task] = set()
    async with httpx.AsyncClient(timeout=TIEMPO_LIMITE_HTTP) as http:
        proveedor = ProveedorToken(config.oauth, http)
        enviadas = 0
        while duracion is None or time.monotonic() - inicio < duracion:
            # Ritmo fijo: la transacción n sale en inicio + n / tps.
            espera = inicio + enviadas * intervalo - time.monotonic()
            if espera > 0:
                await asyncio.sleep(espera)
            if duracion is not None and time.monotonic() - inicio >= duracion:
                break
            tx = generador.siguiente(datetime.now(timezone.utc))
            tarea = asyncio.create_task(enviar_una(tx, config.url_gateway, http, proveedor, resumen))
            tareas.add(tarea)
            tarea.add_done_callback(tareas.discard)
            enviadas += 1
        if tareas:
            await asyncio.gather(*tareas)
    print(f"vivo terminado: {resumen.texto()}")
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
            return asyncio.run(_vivo(config, poblacion, rng, args.tps or config.tps, args.duracion))
        except KeyboardInterrupt:
            return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
