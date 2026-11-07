package com.fraude.scoring.regla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Fila de {@code regla}. Editar no modifica la fila: inserta una versión nueva (§5.2). */
@Entity
@Table(name = "regla")
public class ReglaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String codigo;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String descripcion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> parametros;

    @Column(nullable = false)
    private int puntos;

    @Column(nullable = false)
    private boolean activa;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean vigente;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "creada_por", nullable = false)
    private String creadaPor;

    protected ReglaEntity() {
    }

    ReglaEntity(UUID id, String codigo, String nombre, String descripcion, Map<String, Object> parametros,
            int puntos, boolean activa, int version, Instant creadaEn, String creadaPor) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.parametros = parametros;
        this.puntos = puntos;
        this.activa = activa;
        this.version = version;
        this.vigente = true;
        this.creadaEn = creadaEn;
        this.creadaPor = creadaPor;
    }

    /** Crea la versión siguiente aplicando la edición; los campos nulos se conservan. */
    ReglaEntity siguienteVersion(EdicionRegla e, String usuario, Instant ahora) {
        return new ReglaEntity(UUID.randomUUID(), codigo,
                e.nombre() != null ? e.nombre() : nombre,
                e.descripcion() != null ? e.descripcion() : descripcion,
                e.parametros() != null ? e.parametros() : parametros,
                e.puntos() != null ? e.puntos() : puntos,
                e.activa() != null ? e.activa() : activa,
                version + 1, ahora, usuario);
    }

    void dejarDeSerVigente() {
        this.vigente = false;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public Map<String, Object> getParametros() {
        return parametros;
    }

    public int getPuntos() {
        return puntos;
    }

    public boolean isActiva() {
        return activa;
    }

    public int getVersion() {
        return version;
    }

    public boolean isVigente() {
        return vigente;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public String getCreadaPor() {
        return creadaPor;
    }
}
