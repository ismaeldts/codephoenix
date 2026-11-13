# Paquetes de migración de ejemplo

Resultado real de ejecutar CodePhoenix sobre los dos programas de ejemplo incluidos en
`src/main/resources/samples/`. Cada `.zip` es exactamente lo que descarga el usuario al terminar una migración.

| Paquete | Programa original | Dominio | Se verificó contra |
|---|---|---|---|
| `codephoenix-cobol-AMORTIZA.zip` | `AMORTIZA.cbl` (COBOL) | Créditos: amortización francesa | GnuCOBOL |
| `codephoenix-plsql-LIQUIDA_SINIESTRO.zip` | `LIQUIDA_SINIESTRO.sql` (PL/SQL) | Seguros: liquidación de siniestros | Oracle Database 23ai Free |

## Qué contiene cada paquete

| Ruta | Contenido |
|---|---|
| `README.md` | Indicadores de la migración y cómo ejecutarla |
| `INFORME_EJECUTIVO.md` | Resumen de una página para negocio |
| `src/main/java` | Código Java 21 con arquitectura hexagonal |
| `src/test/java` | Pruebas unitarias JUnit 5 y pasos de Cucumber |
| `src/test/resources/features` | Escenarios Gherkin en español, etiquetados por regla (`@R-01`…) |
| `legacy/` | Programa original con los datos sensibles enmascarados |
| `docs/` | Reglas de negocio y tablas de decisión, matriz de trazabilidad, arquitectura, ADR, OpenAPI |
| `reports/` | Equivalencia, calidad, seguridad (SAST), pruebas, cobertura, auditoría de IA y Golden Master |
| `Dockerfile`, `build.gradle` | Construcción y pruebas con un solo comando |

## Probar un paquete

Descomprímelo y, desde Git Bash, Linux o macOS:

```bash
docker build -t migracion .        # compila y ejecuta las pruebas unitarias y los escenarios Gherkin
# COBOL: un registro de entrada
echo "000100000000180000012700" | docker run -i --rm migracion
# PL/SQL: un caso del Golden Master
docker run -i --rm migracion < reports/golden-master/TC01.in
```

La salida debe ser idéntica al archivo `reports/golden-master/<caso>.legacy.out` correspondiente, que es lo que
produjo el programa original. En Windows PowerShell 5.1 no uses `echo … |` (antepone un carácter invisible);
usa Git Bash o `cmd`.

Los paquetes contienen solo datos sintéticos y el código original enmascarado: ninguna credencial de los
programas de ejemplo aparece en ellos.
