package com.fraude.transacciones.siembra;

import com.fraude.transacciones.maestros.Cliente;
import com.fraude.transacciones.maestros.ClienteRepository;
import com.fraude.transacciones.maestros.Comercio;
import com.fraude.transacciones.maestros.ComercioRepository;
import com.fraude.transacciones.maestros.Dispositivo;
import com.fraude.transacciones.maestros.DispositivoRepository;
import com.fraude.transacciones.maestros.Tarjeta;
import com.fraude.transacciones.maestros.TarjetaRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga idempotente de datos maestros: cada registro se inserta o se actualiza por su
 * identificador, de modo que repetir la siembra no duplica nada.
 */
@Service
public class SiembraService {

    private final ClienteRepository clientes;
    private final TarjetaRepository tarjetas;
    private final ComercioRepository comercios;
    private final DispositivoRepository dispositivos;
    private final Clock reloj;

    public SiembraService(ClienteRepository clientes, TarjetaRepository tarjetas,
            ComercioRepository comercios, DispositivoRepository dispositivos) {
        this.clientes = clientes;
        this.tarjetas = tarjetas;
        this.comercios = comercios;
        this.dispositivos = dispositivos;
        this.reloj = Clock.systemUTC();
    }

    @Transactional
    public SiembraResponse sembrar(SiembraRequest req) {
        Instant ahora = reloj.instant();
        clientes.saveAll(req.clientes().stream()
                .map(c -> new Cliente(c.id(), c.nombre(), c.segmento(), c.ciudadResidencia(), c.fechaAlta()))
                .toList());
        comercios.saveAll(req.comercios().stream()
                .map(c -> new Comercio(c.id(), c.nombre(), c.mcc(), c.pais(), c.ciudad(), c.latitud(),
                        c.longitud(), c.online()))
                .toList());
        tarjetas.saveAll(req.tarjetas().stream()
                .map(t -> new Tarjeta(t.id(), t.clienteId(), t.token(), t.ultimos4(), t.estado(),
                        t.fechaEmision()))
                .toList());
        dispositivos.saveAll(req.dispositivos().stream()
                .map(d -> dispositivos.findById(d.huella())
                        .map(existente -> new Dispositivo(d.huella(), d.clienteId(), existente.getCreadoEn()))
                        .orElseGet(() -> new Dispositivo(d.huella(), d.clienteId(), ahora)))
                .toList());
        return new SiembraResponse(req.clientes().size(), req.tarjetas().size(), req.comercios().size(),
                req.dispositivos().size());
    }

    public record SiembraResponse(int clientes, int tarjetas, int comercios, int dispositivos) {
    }
}
