-- Reglas iniciales R01 a R07, versión 1 (especificación §7.2). Los valores son configurables:
-- editar una regla crea una versión nueva (no se modifica esta migración).

INSERT INTO regla (id, codigo, nombre, descripcion, parametros, puntos, activa, version, vigente, creada_en, creada_por)
VALUES
    (gen_random_uuid(), 'R01', 'Monto inusual',
     'Monto mayor que factor × promedio de 30 días; solo con perfil suficiente',
     '{"factor": 5, "minimoTransacciones": 10}', 40, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R02', 'Velocidad',
     'Cantidad mínima de transacciones previas de la tarjeta en la ventana anterior a la fecha',
     '{"minimoPrevias": 5, "ventanaMinutos": 10}', 30, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R03', 'Viaje imposible',
     'Velocidad implícita desde la última transacción mayor que el máximo; con menos de segundosMinimos se cumple si la distancia supera distanciaMinimaKm. No aplica a ECOMMERCE ni sin coordenadas',
     '{"velocidadMaximaKmH": 800, "segundosMinimos": 60, "distanciaMinimaKm": 50}', 50, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R04', 'País nuevo',
     'País que no está en el perfil de 30 días; solo con perfil suficiente',
     '{"minimoTransacciones": 10}', 20, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R05', 'Dispositivo nuevo',
     'Huella de dispositivo no nula que no está entre los dispositivos conocidos del cliente',
     '{}', 25, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R06', 'Rubro de riesgo',
     'MCC en la lista de rubros de riesgo',
     '{"mccs": ["7995", "6051", "4829"]}', 15, true, 1, true, now(), 'sistema'),
    (gen_random_uuid(), 'R07', 'Horario inusual',
     'Hora local entre horaInicio y horaFin (inclusive) sin transacciones del perfil en esas horas; solo con perfil suficiente',
     '{"horaInicio": 2, "horaFin": 4, "zona": "America/Lima", "minimoTransacciones": 10}', 10, true, 1, true, now(), 'sistema');
