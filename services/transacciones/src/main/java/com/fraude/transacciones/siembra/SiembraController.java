package com.fraude.transacciones.siembra;

import com.fraude.transacciones.siembra.SiembraService.SiembraResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint interno (no expuesto en el gateway) que usa el comando {@code siembra} del generador. */
@RestController
@RequestMapping("/internal/v1/siembra")
public class SiembraController {

    private final SiembraService servicio;

    public SiembraController(SiembraService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public SiembraResponse sembrar(@Valid @RequestBody SiembraRequest req) {
        return servicio.sembrar(req);
    }
}
