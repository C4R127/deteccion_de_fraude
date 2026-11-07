package com.fraude.transacciones.transaccion;

import com.fraude.transacciones.transaccion.RegistroTransaccionService.Registro;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transacciones")
public class TransaccionController {

    private final RegistroTransaccionService registro;

    public TransaccionController(RegistroTransaccionService registro) {
        this.registro = registro;
    }

    /** 201 con la decisión; 200 con la respuesta original si el {@code idExterno} se repite. */
    @PostMapping
    public ResponseEntity<TransaccionResponse> crear(@Valid @RequestBody TransaccionRequest req) {
        Registro r = registro.registrar(req);
        return ResponseEntity.status(r.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(r.respuesta());
    }
}
