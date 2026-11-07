package com.fraude.transacciones.maestros;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TarjetaRepository extends JpaRepository<Tarjeta, UUID> {

    Optional<Tarjeta> findByToken(String token);
}
