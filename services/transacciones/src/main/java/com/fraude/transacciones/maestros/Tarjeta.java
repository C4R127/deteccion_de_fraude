package com.fraude.transacciones.maestros;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Tarjeta identificada solo por token y últimos 4 dígitos; nunca el número completo. */
@Entity
@Table(name = "tarjeta")
public class Tarjeta {

    @Id
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(nullable = false, unique = true)
    private String token;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 4)
    private String ultimos4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTarjeta estado;

    @Column(name = "fecha_emision", nullable = false)
    private Instant fechaEmision;

    protected Tarjeta() {
    }

    public Tarjeta(UUID id, UUID clienteId, String token, String ultimos4, EstadoTarjeta estado,
            Instant fechaEmision) {
        this.id = id;
        this.clienteId = clienteId;
        this.token = token;
        this.ultimos4 = ultimos4;
        this.estado = estado;
        this.fechaEmision = fechaEmision;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClienteId() {
        return clienteId;
    }

    public String getToken() {
        return token;
    }

    public String getUltimos4() {
        return ultimos4;
    }

    public EstadoTarjeta getEstado() {
        return estado;
    }

    public Instant getFechaEmision() {
        return fechaEmision;
    }
}
