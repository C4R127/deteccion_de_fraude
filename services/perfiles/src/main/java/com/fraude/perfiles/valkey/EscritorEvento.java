package com.fraude.perfiles.valkey;

import com.fraude.common.eventos.TransaccionCreadaEvento;

/** Escritura en Valkey que depende de cada evento (no del historial del cliente). */
public interface EscritorEvento {

    void escribir(TransaccionCreadaEvento evento);
}
