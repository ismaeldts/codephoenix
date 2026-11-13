# CodePhoenix — Guía de pruebas

Cómo verificar que CodePhoenix funciona y qué resultado esperar en cada caso.

## 1. Pruebas automáticas del proyecto

```powershell
.\gradlew.bat test
```

No necesitan Docker ni la API de Anthropic para las pruebas de lógica; la prueba de contexto de Spring
necesita la variable `SPRING_AI_ANTHROPIC_API_KEY` definida (no hace llamadas).

| Clase de prueba | Qué verifica |
|---|---|
| `SecretMaskerTest` | Enmascaramiento de credenciales, cadenas de conexión, correos, tarjetas (Luhn) y asignaciones PL/SQL; conserva el número de líneas |
| `CobolStaticAnalyzerTest` | Párrafos, GO TO, código muerto, complejidad, mapeo PIC/COMP-3 → BigDecimal, diseño del registro de entrada |
| `PlsqlStaticAnalyzerTest` | Firma, esquema, tablas leídas/modificadas, código muerto, GOTO, tablas faltantes, llamadas externas, SYSDATE |
| `PlsqlHarnessTest` | Formato de casos `CALL`/`PARAM`/`ROW`, validación contra firma y esquema, script de Oracle (reinicio de secuencias, orden por claves foráneas, normalización de USER/SYSDATE) |
| `SqlInsertParserTest` | `INSERT` posicionales y con columnas, literales (`DATE`, `TO_DATE`, `NULL`), llamadas en bloques anónimos |
| `ArtifactBundlerTest` | Un `.zip` desordenado se ordena en orden de compilación y sus datos de prueba se convierten en un caso |
| `EquivalenceComparatorTest` | Comparación campo a campo, filas de tablas como conjunto, diferencias de centavos |
| `RecordEncoderTest` | Codificación de registros de ancho fijo con decimales implícitos |
| `SecurityAnalyzerTest` | Clasificación de hallazgos de SpotBugs/FindSecBugs y detección de dependencias de terceros |
| `MigrationJobSnapshotTest` | Persistencia sin pérdida y sin el código original |
| `CodephoenixApplicationTests` | El contexto de Spring arranca |

**Resultado esperado:** `BUILD SUCCESSFUL` y 0 fallos.

## 2. Prueba de extremo a extremo con COBOL

1. Arranca CodePhoenix (ver la guía de instalación) y abre <http://localhost:8080>.
2. Elige **AMORTIZA.cbl** y pulsa **Iniciar modernización**.
3. Espera a que la fase 4 pida aprobación (~1 minuto).

| Dónde mirar | Resultado esperado |
|---|---|
| Fase 1 | 4 datos sensibles enmascarados (correo, cadena de conexión DB2, usuario y contraseña); 0 residuales |
| Pestaña *Seguridad y auditoría* | Los 4 hallazgos con su línea y marcador |
| Pestaña *Análisis* | 9 párrafos; `9000-CALCULAR-MORA` en rojo (código muerto); 4 GO TO; mapeo `WS-TASA-MENSUAL` → `BigDecimal(precision=11, scale=10)` |
| Pestaña *Reglas* | 14–16 reglas, cada una con sus líneas resaltadas a la derecha; al menos una tabla de decisión (tramos de score) |

4. Aprueba las reglas (botón *Aprobar las pendientes con confianza ≥ 80 %* y luego *Aprobar especificación*).
5. Espera el final (~5 minutos).

| Dónde mirar | Resultado esperado |
|---|---|
| Barra de fases | Las 8 fases en verde |
| *Verificación* | Equivalencia 100 % (≈18/18); pruebas 100 %; cobertura ≥ 80 % / 70 %; escenarios BDD aprobados; 0 vulnerabilidades críticas |
| *Legacy vs. moderno* | Al elegir una regla se resaltan sus líneas COBOL y el método Java que la implementa |
| *Entrega* | Botón de descarga e informe ejecutivo |

## 3. Prueba de extremo a extremo con PL/SQL

Igual que la anterior con **LIQUIDA_SINIESTRO.sql**.

| Dónde mirar | Resultado esperado |
|---|---|
| Fase 2 | "Oracle: 3 tablas creadas y LIQUIDA_SINIESTRO compilado (VALID)" en la actividad |
| *Análisis* | Firma `PROCEDURE LIQUIDA_SINIESTRO(P_SINIESTRO_ID IN NUMBER, …)`; lee `SINIESTROS, POLIZAS`; modifica `PAGOS_SINIESTRO, SINIESTROS`; `CALCULA_IVA` como código muerto |
| *Reglas* | Incluye las tácitas: "la póliza debe existir" (NO_DATA_FOUND) y el redondeo del factor de coaseguro a 4 decimales |
| *Verificación* | Entradas con líneas `PARAM`/`ROW`; salidas con `OUT`, `ERR|-20010|…` o `ERR|100|no data found` y el estado final de las tablas |

## 4. Prueba del paquete entregado

Descomprime el `.zip` y, desde Git Bash:

```bash
docker build -t migracion .                     # compila, ejecuta pruebas unitarias y escenarios Gherkin
# COBOL
echo "000100000000180000012700" | docker run -i --rm migracion
# PL/SQL
docker run -i --rm migracion < reports/golden-master/TC01.in
```

**Resultado esperado:** la imagen se construye (si una prueba fallara, no se construiría) y la salida es
idéntica a `reports/golden-master/<caso>.legacy.out`.

Para COBOL, la primera línea debe ser `HDR| 18.0000| 12|     9168.00` y la última
`TOT|      10015.99|        300.46|     110316.45`.

## 5. Casos de error y manejo controlado

| Prueba | Cómo | Resultado esperado |
|---|---|---|
| Artefacto vacío | Borrar el código y pulsar *Iniciar* | Mensaje "El artefacto está vacío…" sin crear migración |
| Lenguaje no soportado | Pegar código Java o RPG | Fase 1 detenida: "Lenguaje detectado: … Esta versión ejecuta el Golden Master para COBOL y PL/SQL" |
| COBOL incompleto | Borrar la línea `PROCEDURE DIVISION.` | Fase 1 detenida: "El artefacto no compila en GnuCOBOL…" con el error del compilador |
| PL/SQL que llama a rutinas ausentes | Añadir `AUDITORIA.REGISTRAR(p_siniestro_id);` | Fase 2 detenida: "Dependencias sin resolver: AUDITORIA.REGISTRAR. Incluye su código…" |
| PL/SQL sin DDL | Borrar los `CREATE TABLE` | El Analista de datos infiere el esquema; la actividad lo marca como supuesto y la tabla aparece como *inferida* |
| Rechazar sin motivo | Pulsar *Rechazar* sin comentario | "Indica el motivo del rechazo para la auditoría." |
| Aprobar con reglas pendientes | Llamar a `POST /approve` con reglas sin decidir | HTTP 400 "Faltan N reglas por revisar" |
| Docker apagado | Detener Docker Desktop e iniciar una migración | Fase 1 detenida con la instrucción para iniciar Docker y construir la imagen |
| Reinicio del servidor | Detener CodePhoenix con una migración esperando aprobación y volver a arrancarlo | La migración reaparece en *Migraciones anteriores* y se puede aprobar |

## 6. Prueba de usabilidad (criterio del reto)

El reto pide que **al menos un usuario no involucrado en el desarrollo complete la tarea sin asistencia**.

1. Elige a alguien que no haya visto CodePhoenix (idealmente un perfil de negocio y otro técnico).
2. Dale solo esta instrucción: *"Moderniza el programa de ejemplo de créditos y descarga el resultado."*
3. No intervengas. Cronometra y anota dónde duda.
4. Al terminar, pregúntale: ¿entendiste qué hacía cada fase?, ¿confías en el resultado?, ¿qué cambiarías?

| Registro | Valor |
|---|---|
| Participante (rol) | |
| Completó sin ayuda (sí/no) | |
| Tiempo total | |
| Puntos de duda | |
| Comentarios | |

## 7. Criterios de aceptación

La versión se considera lista para la demo si:

- [ ] `.\gradlew.bat test` termina sin fallos.
- [ ] La migración COBOL y la PL/SQL completan las 8 fases en verde.
- [ ] Ambos paquetes se construyen con `docker build` y reproducen el Golden Master.
- [ ] `docker compose up --build` deja la aplicación funcionando en <http://localhost:8080>.
- [ ] Al menos una migración completada aparece en *Migraciones anteriores* tras reiniciar (plan de contingencia).
- [ ] Un usuario externo completó la prueba de usabilidad.
