# Mapeo de semántica COBOL a Java (BigDecimal)

Reglas verificadas para reproducir exactamente la aritmética de GnuCOBOL / IBM Enterprise COBOL.

## Tipos
| COBOL | Java |
|---|---|
| `PIC 9(n)` (n <= 9) | `int` o `BigDecimal` con escala 0 si participa en cálculos monetarios |
| `PIC 9(n)V9(m)` / `COMP-3` | `BigDecimal` con escala `m` exacta. Nunca `double`/`float` |
| `PIC S9(n)V9(m)` | `BigDecimal` con signo |
| `PIC X(n)` | `String` |
| `PIC Z(n)9.99` (editado) | Solo formato de salida: ver sección "Formato de salida" |
| Decimales implícitos (`V`) en un registro de entrada | Parsear los dígitos y aplicar `movePointLeft(m)` / `BigDecimal.valueOf(long, m)` |

## Asignación y aritmética
- `COMPUTE X = expr` **sin** `ROUNDED`: el resultado se **trunca** a la escala de X → `setScale(escalaX, RoundingMode.DOWN)`.
- `COMPUTE X ROUNDED = expr`: redondeo mitad hacia arriba (lejos de cero) → `setScale(escalaX, RoundingMode.HALF_UP)`.
- Las expresiones intermedias se evalúan con precisión amplia. Para divisiones intermedias usa
  `MathContext` de 40 dígitos (`new MathContext(40, RoundingMode.HALF_UP)`) y aplica el truncamiento o
  redondeo SOLO al almacenar en el campo destino.
- `ADD a TO b`, `SUBTRACT a FROM b`: suma exacta; el resultado toma la escala de `b` (truncando si hiciera falta).
- `MOVE` numérico a un campo con menos decimales trunca los decimales sobrantes (`RoundingMode.DOWN`).
- Un bucle `COMPUTE F = F * (1 + I)` repetido n veces trunca F a su escala **en cada iteración**: reprodúcelo
  con el mismo bucle y el mismo `setScale(escalaF, RoundingMode.DOWN)` en cada paso. No uses `pow()`.
- **Campos sin signo** (`PIC 9...` sin `S`): al almacenar un resultado negativo (COMPUTE, MOVE, SUBTRACT)
  se pierde el signo y queda el **valor absoluto**. Ejemplo: si el saldo con signo es -581.75, un
  `COMPUTE WS-INTERES ROUNDED = WS-SALDO * I` con WS-INTERES sin signo guarda +17.45, y
  `MOVE WS-SALDO TO OUT-SALDO` muestra 581.75. Reprodúcelo con `.abs()` al asignar a ese campo; los campos
  con `S` conservan el signo. Es un comportamiento legacy que hay que preservar (y reportar como hallazgo).
- `INITIALIZE` pone los numéricos a cero y los alfanuméricos a espacios.
- Constantes `VALUE`: créalas con `new BigDecimal("...")` (cadena), nunca desde `double`.

## Validaciones y flujo de control
- `IF registro NOT NUMERIC`: falso si cualquier posición del registro no es un dígito 0-9 o si el registro es
  más corto que su longitud declarada (ACCEPT rellena con espacios). Un registro más largo se trunca a la
  longitud declarada antes de validar.
- `PERFORM X THRU X-EXIT` con `GO TO X-EXIT`: equivale a una cadena de validaciones con retorno temprano;
  la primera condición que falla determina el resultado. Conserva el ORDEN de las validaciones.
- `EVALUATE TRUE ... WHEN` evalúa en orden y ejecuta solo la primera rama verdadera.

## Formato de salida (DISPLAY)
- `DISPLAY a b c` concatena los campos sin separadores ni espacios adicionales.
- Un campo editado `Z(n)9.99` ocupa exactamente n+4 caracteres: los ceros a la izquierda se sustituyen por
  espacios y siempre hay al menos un dígito antes del punto. Equivalente: `String.format("%" + ancho + "s",
  valor.setScale(2).toPlainString())` para valores no negativos.
- `ZZ9` ocupa 3 caracteres; `ZZ9.9999` ocupa 8 caracteres.
- `FUNCTION TRIM(campo)` elimina los espacios a ambos lados.
