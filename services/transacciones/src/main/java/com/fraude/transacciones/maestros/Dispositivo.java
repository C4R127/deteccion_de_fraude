package com.fraude.transacciones.maestros;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Dispositivo conocido de un cliente, cargado por la siembra (ADR 0005). */
@Entity
@Table(name = "dispositivo")
public class Dispositivo {

    @Id
    private String huella;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected Dispositivo() {
    }

    public Dispositivo(String huella, UUID clienteId, Instant creadoEn) {
        this.huella = huella;
        this.clienteId = clienteId;
        this.creadoEn = creadoEn;
    }

    public String getHuella() {
        return huella;
    }

    public UUID getClienteId() {
        return clienteId;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
