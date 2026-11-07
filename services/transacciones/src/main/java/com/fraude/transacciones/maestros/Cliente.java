package com.fraude.transacciones.maestros;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Segmento segmento;

    @Column(name = "ciudad_residencia", nullable = false)
    private String ciudadResidencia;

    @Column(name = "fecha_alta", nullable = false)
    private Instant fechaAlta;

    protected Cliente() {
    }

    public Cliente(UUID id, String nombre, Segmento segmento, String ciudadResidencia, Instant fechaAlta) {
        this.id = id;
        this.nombre = nombre;
        this.segmento = segmento;
        this.ciudadResidencia = ciudadResidencia;
        this.fechaAlta = fechaAlta;
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public Segmento getSegmento() {
        return segmento;
    }

    public String getCiudadResidencia() {
        return ciudadResidencia;
    }

    public Instant getFechaAlta() {
        return fechaAlta;
    }
}
