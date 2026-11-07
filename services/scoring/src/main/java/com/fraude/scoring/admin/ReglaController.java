package com.fraude.scoring.admin;

import com.fraude.scoring.regla.CatalogoReglas;
import com.fraude.scoring.regla.EdicionRegla;
import com.fraude.scoring.regla.VersionRegla;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Editor de reglas (§11, ADR 0014): editar crea la versión siguiente; no se crean reglas. */
@RestController
@RequestMapping("/api/v1/reglas")
public class ReglaController {

    private final CatalogoReglas catalogo;

    public ReglaController(CatalogoReglas catalogo) {
        this.catalogo = catalogo;
    }

    public record EdicionReglaRequest(String nombre, String descripcion, Map<String, Object> parametros,
            @Min(0) Integer puntos, Boolean activa) {
    }

    @GetMapping
    public List<VersionRegla> vigentes() {
        return catalogo.vigentes().stream().map(VersionRegla::de).toList();
    }

    @GetMapping("/{codigo}")
    public VersionRegla vigente(@PathVariable String codigo) {
        return VersionRegla.de(catalogo.vigente(codigo));
    }

    @GetMapping("/{codigo}/versiones")
    public List<VersionRegla> versiones(@PathVariable String codigo) {
        return catalogo.versiones(codigo);
    }

    @PutMapping("/{codigo}")
    public VersionRegla editar(@PathVariable String codigo, @Valid @RequestBody EdicionReglaRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        var edicion = new EdicionRegla(req.nombre(), req.descripcion(), req.parametros(), req.puntos(), req.activa());
        return VersionRegla.de(catalogo.editar(codigo, edicion, Usuario.de(jwt)));
    }
}
