package com.fraude.transacciones.maestros;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "comercio")
public class Comercio {

    @Id
    private String id;

    @Column(nullable = false)
    private String nombre;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 4)
    private String mcc;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String pais;

    @Column(nullable = false)
    private String ciudad;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitud;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitud;

    @Column(nullable = false)
    private boolean online;

    protected Comercio() {
    }

    public Comercio(String id, String nombre, String mcc, String pais, String ciudad, BigDecimal latitud,
            BigDecimal longitud, boolean online) {
        this.id = id;
        this.nombre = nombre;
        this.mcc = mcc;
        this.pais = pais;
        this.ciudad = ciudad;
        this.latitud = latitud;
        this.longitud = longitud;
        this.online = online;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getMcc() {
        return mcc;
    }

    public String getPais() {
        return pais;
    }

    public String getCiudad() {
        return ciudad;
    }

    public BigDecimal getLatitud() {
        return latitud;
    }

    public BigDecimal getLongitud() {
        return longitud;
    }

    public boolean isOnline() {
        return online;
    }
}
