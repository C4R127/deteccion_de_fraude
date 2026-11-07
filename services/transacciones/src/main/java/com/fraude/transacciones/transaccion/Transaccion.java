package com.fraude.transacciones.transaccion;

import com.fraude.common.eventos.Canal;
import com.fraude.common.eventos.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Transacción recibida (§5.1). {@code decision} es nula mientras está pendiente. */
@Entity
@Table(name = "transaccion")
public class Transaccion {

    @Id
    private UUID id;

    @Column(name = "id_externo", nullable = false, unique = true)
    private UUID idExterno;

    @Column(name = "tarjeta_id", nullable = false)
    private UUID tarjetaId;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(name = "comercio_id", nullable = false)
    private String comercioId;

    @Column(name = "dispositivo_huella")
    private String dispositivoHuella;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String moneda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Canal canal;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String pais;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitud;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitud;

    @Column(nullable = false)
    private Instant fecha;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private Integer score;

    @Column(nullable = false)
    private boolean respaldo;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    protected Transaccion() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getIdExterno() {
        return idExterno;
    }

    public Decision getDecision() {
        return decision;
    }

    public Integer getScore() {
        return score;
    }

    public boolean isRespaldo() {
        return respaldo;
    }
}
