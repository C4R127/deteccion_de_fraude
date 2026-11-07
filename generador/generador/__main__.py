"""CLI del generador: ``seco`` (JSONL sin API) y ``siembra`` (datos maestros)."""

import argparse
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import numpy as np

from generador.clientes import crear_poblacion
from generador.config import cargar_config
from generador.normal import generar_normal
from generador.siembra import enviar_siembra

CONFIG_POR_DEFECTO = Path(__file__).resolve().parents[1] / "config.yaml"
TIEMPO_LIMITE_SIEMBRA = 60.0


def _argumentos(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python -m generador")
    parser.add_argument("--config", type=Path, default=CONFIG_POR_DEFECTO,
                        help="ruta de config.yaml")
    sub = parser.add_subparsers(dest="comando", required=True)

    seco = sub.add_parser("seco", help="imprime transacciones normales en JSONL sin llamar a la API")
    seco.add_argument("--dias", type=int, required=True, help="días hacia atrás desde hoy")
    seco.add_argument("--clientes", type=int, help="sobrescribe la cantidad de clientes")

    siembra = sub.add_parser("siembra", help="crea clientes, tarjetas, comercios y dispositivos")
    siembra.add_argument("--clientes", type=int, help="sobrescribe la cantidad de clientes")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _argumentos(argv)
    config = cargar_config(args.config)
    if args.clientes is not None:
        config = config.model_copy(update={"clientes": args.clientes})
    rng = np.random.default_rng(config.semilla)
    poblacion = crear_poblacion(config, rng)

    if args.comando == "seco":
        hasta = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        desde = hasta - timedelta(days=args.dias)
        for tx in generar_normal(poblacion, desde, hasta, rng):
            sys.stdout.write(tx.a_json() + "\n")
        return 0

    if args.comando == "siembra":
        try:
            with httpx.Client(timeout=TIEMPO_LIMITE_SIEMBRA) as cliente:
                resumen = enviar_siembra(poblacion, config.url_transacciones, cliente)
        except httpx.HTTPStatusError as error:
            print(f"siembra fallida: HTTP {error.response.status_code} {error.response.text}",
                  file=sys.stderr)
            return 1
        except httpx.HTTPError as error:
            print(f"siembra fallida: {error}", file=sys.stderr)
            return 1
        print("siembra completa: " + ", ".join(f"{k}={v}" for k, v in resumen.items()))
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
