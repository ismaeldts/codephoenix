# CodePhoenix · Copiloto de Modernización y Descubrimiento de Código Legacy

CodePhoenix convierte programas **COBOL** y **PL/SQL** en código Java moderno **cuya equivalencia funcional se
demuestra** ejecutando el programa original y el nuevo, con las mismas entradas, dentro de un sandbox sin acceso a
Internet.

```
COBOL / PL/SQL ──► enmascarar ──► análisis estático ──► reglas de negocio ──► APROBACIÓN HUMANA
                                                                                   │
      paquete .zip ◄── documentación ◄── pruebas + cobertura ◄── equivalencia ◄── código Java ◄── arquitectura
```

## Documentación

| Documento | Para quién |
|---|---|
| [Resumen ejecutivo](docs/01-resumen-ejecutivo.md) | Negocio, dirección, jurado |
| [Arquitectura técnica](docs/02-arquitectura-tecnica.md) | Desarrolladores y arquitectos (con diagramas) |
| [Guía de pruebas](docs/03-guia-de-pruebas.md) | QA y evaluadores: qué probar y qué esperar |
| [Instalación y herramientas](docs/04-instalacion-y-herramientas.md) | Quien despliega; solución de problemas |
| [Guion de demo](docs/05-guion-de-demo.md) | Presentadores |

## Puesta en marcha con un solo comando

```bash
export SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...      # PowerShell: $env:SPRING_AI_ANTHROPIC_API_KEY = "sk-ant-..."
docker compose up --build -d
```

Necesita Docker Desktop. La primera vez construye la aplicación y el sandbox y descarga Oracle (~6 GB).
Modo desarrollo (sin contenerizar la aplicación): ver [Instalación](docs/04-instalacion-y-herramientas.md).

Abre <http://localhost:8080>, elige un ejemplo y pulsa **Iniciar modernización**:

| Ejemplo | Lenguaje | Dominio | Se ejecuta en |
|---|---|---|---|
| `AMORTIZA.cbl` | COBOL | Créditos: amortización francesa | GnuCOBOL |
| `LIQUIDA_SINIESTRO.sql` | PL/SQL | Seguros: liquidación de siniestros | Oracle Database 23ai Free |

## Lenguajes soportados

Todo lo que depende del lenguaje está detrás de la interfaz `LegacyPlatform` (patrón Strategy): el orquestador,
los agentes y los quality gates son los mismos para todos.

| | COBOL | PL/SQL |
|---|---|---|
| Análisis estático | Párrafos, PERFORM/GO TO, PIC/COMP-3 → BigDecimal, registro de entrada | Firma, unidades, DDL, tablas leídas y modificadas, `NUMBER(p,s)` → BigDecimal, funciones no deterministas |
| Ejecución del original | GnuCOBOL en un contenedor sin red | Oracle Free en un contenedor caliente, en una red Docker interna (IP privada, sin salida a Internet); un esquema nuevo por ejecución |
| Caso de prueba | Un registro de ancho fijo (lo codifica CodePhoenix a partir de valores por campo) | Parámetros de entrada + filas iniciales de las tablas (`PARAM|...`, `ROW|...`) |
| Qué se compara | Toda la salida (DISPLAY) | Parámetros de salida o error (`SQLCODE` y mensaje) **y** el estado final de las tablas modificadas |
| Esquema faltante | — | Si el artefacto no trae el DDL, el Analista de datos lo infiere y queda marcado como supuesto |

Oracle tarda ~1 minuto en arrancar la primera vez; CodePhoenix lo inicia en segundo plano al arrancar y lo
reutiliza (`docker rm -f codephoenix-oracle` lo elimina).

## Flujo de 8 fases con quality gates

| Fase | Qué hace | Quality gate |
|---|---|---|
| 1. Ingesta | Detecta el lenguaje, enmascara secretos y datos personales, verifica que el artefacto es ejecutable | 0 secretos residuales hacia la IA |
| 2. Análisis estático | Parser determinista: unidades, grafo de llamadas, código muerto, complejidad, mapeo de tipos a Java, mapa de riesgo; en PL/SQL crea el esquema y compila en Oracle | 100% de dependencias resueltas; el original compila |
| 3. Reglas de negocio | Agente Extractor: reglas condición/acción, tablas de decisión, Gherkin, líneas de origen y nivel de confianza | Todas las reglas trazadas a líneas válidas |
| 4. Validación humana | El ingeniero aprueba, ajusta o rechaza cada regla; la especificación se versiona | Aprobación explícita |
| 5. Arquitectura | Agente Arquitecto: DDD + hexagonal, ADRs, OpenAPI, diagrama | Dominio, puertos, adaptadores y ADRs |
| 6. Generación | Agente QA diseña casos y se captura el **Golden Master del programa original**; Agente Desarrollador genera Java y se autocorrige con los errores del compilador, del análisis estático y del SAST | Compila; 0 hallazgos bloqueantes o críticos (calidad y seguridad) |
| 7. Verificación | Equivalencia caso por caso contra el original (con autocorrección), pruebas JUnit 5 + JaCoCo, escenarios Gherkin ejecutables (Cucumber), SAST final (SpotBugs + FindSecBugs) | 100% equivalencia, pruebas y escenarios; cobertura ≥ 80% / 70%; complejidad ≤ 10; 0 vulnerabilidades críticas |
| 8. Entrega | Agente Documentador + empaquetado: código, pruebas, reglas, trazabilidad, ADRs, reportes, Dockerfile, informe ejecutivo | Paquete ejecutable con un solo comando |

## Arquitectura de CodePhoenix

| Capa | Paquete | Componentes |
|---|---|---|
| Experiencia | `web`, `static/` | API REST y portal con flujo guiado, revisión de reglas, vista comparativa y tablero |
| Orquestación multiagente | `application` | `MigrationOrchestrator` con estado, quality gates y pausa para aprobación humana |
| Agentes | `infrastructure.ai` | Analista, Extractor de reglas, Arquitecto, QA/Tester, Desarrollador y Documentador |
| Conocimiento (RAG) | `resources/knowledge` | Semántica COBOL → Java, estándares de código y glosario bancario |
| Análisis estático | `infrastructure.analysis` | Parsers COBOL y PL/SQL, enmascarador de secretos, calidad Java, SAST |
| Verificación | `infrastructure.sandbox` | Sandbox efímero sin red (GnuCOBOL, JDK 21, JUnit 5, JaCoCo, SpotBugs, Cucumber) y Oracle en red interna |
| Gobierno | `AgentGateway` | Bitácora de cada llamada a la IA: hash del prompt, tokens, duración, resultado |

Detalle, diagramas y contratos en [Arquitectura técnica](docs/02-arquitectura-tecnica.md).

## Demo

Guion de 10 minutos con lo que hay que mostrar y decir en cada paso: [Guion de demo](docs/05-guion-de-demo.md).

**Plan de contingencia:** cada migración se guarda en `workspace/jobs/<id>/job.json` y sobrevive a los reinicios.
Antes de la demo, ejecuta una migración completa: si falla la conectividad, ábrela desde *Migraciones anteriores* en la
pantalla de inicio. Todas las pestañas, el tablero y la descarga del paquete funcionan sin llamar a la IA. Guarda
también una grabación de pantalla como último respaldo.

## Persistencia

| Estado al detenerse el servidor | Al volver a arrancar |
|---|---|
| Completada | Se reabre tal cual, con el paquete descargable |
| Esperando aprobación | Se puede seguir revisando y aprobar; el flujo continúa normalmente |
| En curso | Se marca como detenida, con el motivo; hay que lanzar una nueva migración |

`job.json` nunca contiene el código original: solo la versión enmascarada. El original queda únicamente en
`workspace/jobs/<id>/legacy/`, que está excluido de git.
