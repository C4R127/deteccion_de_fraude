"""Configuración común de las pruebas del generador."""

import pytest


@pytest.fixture(autouse=True)
def secreto_del_cliente_oauth(monkeypatch):
    """Secreto falso del cliente OAuth para todas las pruebas.

    Sin esto, las pruebas que llaman a la API (mockeada con respx) leerían el ``.env`` real de
    la raíz: pasarían en una máquina con ``.env`` y fallarían en CI, que no lo tiene.
    """
    monkeypatch.setenv("GENERADOR_CLIENT_SECRET", "secreto-de-prueba")
