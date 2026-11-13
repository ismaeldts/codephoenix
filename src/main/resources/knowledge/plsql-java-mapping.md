# Mapeo de semántica PL/SQL (Oracle) a Java

Reglas verificadas para reproducir exactamente el comportamiento de Oracle Database.

## Tipos
| Oracle | Java |
|---|---|
| `NUMBER(p,s)` con s > 0 | `BigDecimal` con escala `s`. Al **asignar** a la variable o columna Oracle **redondea** (HALF_UP) a la escala `s`: `setScale(s, RoundingMode.HALF_UP)` |
| `NUMBER(p)` / `NUMBER(p,0)` | entero (`int`/`long`); al asignar un valor con decimales se **redondea** HALF_UP |
| `NUMBER` sin precisión | `BigDecimal` sin cambio de escala (precisión decimal de 38 dígitos; usa `MathContext(38, HALF_UP)` en divisiones) |
| `PLS_INTEGER`, `BINARY_INTEGER` | `int` (asignar un decimal redondea al entero más cercano) |
| `VARCHAR2(n)`, `CHAR(n)` | `String`. La cadena vacía `''` **es NULL** en Oracle |
| `DATE` | `LocalDateTime` (incluye hora). `fecha + n` suma n días; `fecha1 - fecha2` da días (decimal) |
| `T%ROWTYPE` | record/objeto con las columnas de la tabla T |
| `T.C%TYPE` | el tipo de la columna C de T |

## NULL (fuente habitual de reglas tácitas)
- Cualquier operación aritmética con NULL da NULL. `NVL(x, y)` devuelve y si x es NULL.
- Una comparación con NULL (`=`, `<>`, `<`, `IN`) es **desconocida**: un `IF` con condición desconocida
  **no entra** y ejecuta el `ELSE`. `x <> 'A'` con x NULL NO es verdadero.
- `GREATEST`/`LEAST` devuelven NULL si algún argumento es NULL.

## Funciones
- `ROUND(n, d)`: HALF_UP (mitad lejos de cero). `TRUNC(n, d)`: hacia cero (`RoundingMode.DOWN`).
- `MOD(a, b)` conserva el signo de `a`; `MOD(a, 0)` = a.
- `GREATEST(a, b)`, `LEAST(a, b)` sobre números: máximo y mínimo.

## SQL y transacciones
- `SELECT ... INTO` sin filas lanza `NO_DATA_FOUND` (SQLCODE **100**, mensaje `no data found`);
  con más de una fila lanza `TOO_MANY_ROWS` (SQLCODE -1422).
- `RAISE_APPLICATION_ERROR(-20xxx, 'MSG')` produce SQLCODE -20xxx y mensaje 'MSG'.
- Si una excepción sale de un procedimiento, los `INSERT`/`UPDATE` que el procedimiento ya ejecutó **no se
  deshacen** automáticamente: el estado de las tablas refleja lo ejecutado hasta la excepción.
- `UPDATE` sin filas afectadas no es un error.
- `GOTO etiqueta` salta hacia adelante: equivale a un retorno temprano hacia el bloque final común.

## Contrato de caracterización
- Números en la salida: sin ceros sobrantes ni notación científica: `valor.stripTrailingZeros().toPlainString()`
  (0 se escribe `0`, 10661.10 se escribe `10661.1`, 500.00 se escribe `500`).
- Fechas: `yyyy-MM-dd HH:mm:ss`. NULL: `\N`.
