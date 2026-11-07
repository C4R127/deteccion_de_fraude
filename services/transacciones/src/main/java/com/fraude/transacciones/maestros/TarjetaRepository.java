package com.fraude.transacciones.maestros;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TarjetaRepository extends JpaRepository<Tarjeta, UUID> {
}
