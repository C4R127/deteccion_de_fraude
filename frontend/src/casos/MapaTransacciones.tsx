import 'leaflet/dist/leaflet.css';
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip } from 'react-leaflet';
import { formatearFecha, formatearMonto } from '@/lib/formato';
import type { TransaccionResumen } from './tipos';

/**
 * Ubicaciones de las últimas transacciones físicas del cliente, unidas en orden de fecha
 * (§8.3). Las ECOMMERCE no tienen ubicación y no aparecen. La del caso va resaltada.
 */
export function MapaTransacciones({ transacciones, transaccionCaso }: {
  transacciones: TransaccionResumen[];
  transaccionCaso: string;
}) {
  const fisicas = transacciones
    .filter((t) => t.latitud !== null && t.longitud !== null)
    .sort((a, b) => a.fecha.localeCompare(b.fecha));
  if (fisicas.length === 0) {
    return <p className="text-sm text-muted-foreground">Sin transacciones con ubicación (las compras en línea no tienen).</p>;
  }
  const puntos = fisicas.map((t) => [t.latitud!, t.longitud!] as [number, number]);

  return (
    <MapContainer bounds={puntos} boundsOptions={{ padding: [30, 30], maxZoom: 12 }} scrollWheelZoom={false}
      className="h-80 w-full rounded-lg border">
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <Polyline positions={puntos} pathOptions={{ color: '#64748b', weight: 2, dashArray: '4 6' }} />
      {fisicas.map((t) => {
        const delCaso = t.id === transaccionCaso;
        return (
          <CircleMarker key={t.id} center={[t.latitud!, t.longitud!]} radius={delCaso ? 10 : 6}
            pathOptions={{ color: delCaso ? '#dc2626' : '#0f172a', fillOpacity: delCaso ? 0.7 : 0.4 }}>
            <Tooltip>
              {formatearFecha(t.fecha)} · {t.comercioNombre} · {formatearMonto(t.monto)}
            </Tooltip>
          </CircleMarker>
        );
      })}
    </MapContainer>
  );
}
