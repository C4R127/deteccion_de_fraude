package com.fraude.scoring.regla;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Parámetros de una regla (jsonb) con lectura tipada; un nombre ausente es un error. */
public record Parametros(Map<String, Object> valores) {

    public Parametros {
        valores = valores == null ? Map.of() : Map.copyOf(valores);
    }

    private Object valor(String nombre) {
        Object v = valores.get(nombre);
        if (v == null) {
            throw new IllegalArgumentException("Falta el parámetro '" + nombre + "'");
        }
        return v;
    }

    public int entero(String nombre) {
        Object v = valor(nombre);
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).intValueExact();
        }
        throw new IllegalArgumentException("El parámetro '" + nombre + "' no es un entero: " + v);
    }

    public BigDecimal decimal(String nombre) {
        Object v = valor(nombre);
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        throw new IllegalArgumentException("El parámetro '" + nombre + "' no es un número: " + v);
    }

    public String texto(String nombre) {
        return String.valueOf(valor(nombre));
    }

    public List<String> textos(String nombre) {
        Object v = valor(nombre);
        if (v instanceof List<?> lista) {
            return lista.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("El parámetro '" + nombre + "' no es una lista: " + v);
    }
}
