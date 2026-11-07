package com.fraude.scoring.regla;

import com.fraude.common.errores.RecursoNoEncontradoException;
import com.fraude.common.errores.SolicitudInvalidaException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caché en memoria de las reglas vigentes; el motor la lee en cada evaluación sin ir a la
 * base de datos. Editar inserta la versión siguiente, marca la anterior como no vigente en
 * la misma transacción y recarga la caché.
 */
@Service
public class CatalogoReglas {

    private final ReglaRepository repositorio;
    private final TransactionTemplate tx;
    private final Clock reloj = Clock.systemUTC();
    private volatile Map<String, ReglaVigente> vigentes = Map.of();

    public CatalogoReglas(ReglaRepository repositorio, TransactionTemplate tx) {
        this.repositorio = repositorio;
        this.tx = tx;
        recargar();
    }

    public void recargar() {
        Map<String, ReglaVigente> nuevas = new LinkedHashMap<>();
        for (ReglaEntity e : repositorio.findByVigenteTrueOrderByCodigo()) {
            nuevas.put(e.getCodigo(), ReglaVigente.de(e));
        }
        vigentes = Map.copyOf(nuevas);
    }

    /** Reglas vigentes ordenadas por código (activas e inactivas). */
    public List<ReglaVigente> vigentes() {
        return vigentes.values().stream().sorted((a, b) -> a.codigo().compareTo(b.codigo())).toList();
    }

    public ReglaVigente vigente(String codigo) {
        ReglaVigente regla = vigentes.get(codigo);
        if (regla == null) {
            throw new RecursoNoEncontradoException("La regla " + codigo + " no existe");
        }
        return regla;
    }

    /** Historial de versiones de una regla, de la más nueva a la más antigua. */
    public List<VersionRegla> versiones(String codigo) {
        List<ReglaEntity> filas = repositorio.findByCodigoOrderByVersionDesc(codigo);
        if (filas.isEmpty()) {
            throw new RecursoNoEncontradoException("La regla " + codigo + " no existe");
        }
        return filas.stream().map(VersionRegla::de).toList();
    }

    public ReglaVigente editar(String codigo, EdicionRegla edicion, String usuario) {
        ReglaVigente nueva = tx.execute(estado -> {
            ReglaEntity actual = repositorio.findByCodigoAndVigenteTrue(codigo)
                    .orElseThrow(() -> new RecursoNoEncontradoException("La regla " + codigo + " no existe"));
            validar(actual, edicion);
            actual.dejarDeSerVigente();
            // Primero se libera el índice único de vigentes y luego se inserta la nueva.
            repositorio.saveAndFlush(actual);
            return ReglaVigente.de(repositorio.saveAndFlush(actual.siguienteVersion(edicion, usuario, reloj.instant())));
        });
        recargar();
        return nueva;
    }

    /** Los parámetros editados deben ser los mismos que usa la regla (el motor los lee por nombre). */
    private static void validar(ReglaEntity actual, EdicionRegla edicion) {
        if (edicion.puntos() != null && edicion.puntos() < 0) {
            throw new SolicitudInvalidaException("Los puntos no pueden ser negativos");
        }
        if (edicion.parametros() != null && !edicion.parametros().keySet().equals(actual.getParametros().keySet())) {
            throw new SolicitudInvalidaException("Los parámetros de " + actual.getCodigo() + " deben ser exactamente "
                    + actual.getParametros().keySet());
        }
    }
}
