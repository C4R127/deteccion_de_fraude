# ADR 0004: un esquema de PostgreSQL por servicio

- Estado: aceptada
- Fecha: 2026-11-07 (registra la decisión de la especificación §5)

## Contexto

transacciones, scoring y casos guardan datos en PostgreSQL. Levantar una base por
servicio en local es pesado. Compartir tablas, en cambio, acopla los servicios: un
cambio de columna en uno rompe a otro y nadie sabe quién es dueño del dato.

## Decisión

- Una sola instancia de PostgreSQL 16 con **un esquema y un usuario por servicio**:
  `transacciones`, `scoring` y `casos`. Cada usuario solo tiene permisos sobre su
  esquema (`infra/postgres/init.sh`).
- Cada servicio migra su esquema con su propia carpeta de Flyway.
- **Ningún servicio lee ni escribe el esquema de otro.** Lo que necesita de otro servicio
  le llega por evento de Kafka o por su API.

## Alternativas descartadas

- **Una base de datos por servicio:** el mismo aislamiento, pero más contenedores y más
  memoria en local sin ganancia real para el portafolio.
- **Esquema compartido:** consultas cruzadas fáciles, pero acoplamiento fuerte.

## Consecuencias

- El aislamiento lo hacen cumplir los permisos de la base de datos, no solo la
  disciplina.
- Los datos que cruzan servicios se resuelven sin leer esquemas ajenos:
  - el nombre enmascarado lo sirve transacciones (ADR 0016);
  - cada servicio expone sus propias métricas (ADR 0017);
  - el cierre de un caso llega a scoring como evento (§8.2).
- Separar las instancias más adelante no exige cambiar código, solo la conexión.
