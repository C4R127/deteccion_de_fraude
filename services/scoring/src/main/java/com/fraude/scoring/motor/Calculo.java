package com.fraude.scoring.motor;

import com.fraude.common.eventos.Decision;
import com.fraude.common.eventos.ReglaDisparada;
import java.util.List;
import java.util.Map;

/** Resultado del motor antes de persistir: score con tope 100, decisión y trazabilidad. */
public record Calculo(int score, Decision decision, List<ReglaDisparada> reglasDisparadas,
        Map<String, Integer> versionesReglas) {
}
