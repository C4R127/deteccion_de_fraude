package com.fraude.perfiles.perfil;

/** Destino del perfil calculado; en producción, el hash {@code perfil:{clienteId}} de Valkey. */
public interface EscritorPerfil {

    void escribir(String clienteId, Perfil perfil);
}
