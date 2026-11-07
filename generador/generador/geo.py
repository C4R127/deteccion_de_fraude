"""Distancias geográficas (la misma fórmula que usa R03 en el scoring)."""

from math import asin, cos, radians, sin, sqrt

RADIO_TIERRA_KM = 6371.0


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    return 2 * RADIO_TIERRA_KM * asin(sqrt(a))
