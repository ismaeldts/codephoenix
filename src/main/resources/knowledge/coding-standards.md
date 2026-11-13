# Estándares de codificación del código destino

- Java 21. Solo la biblioteca estándar del JDK en producción (sin Spring, sin Lombok).
- Arquitectura hexagonal: el paquete `domain` no importa nada de `application` ni de `adapter`,
  ni realiza E/S (`java.io`, `System.out`, `System.in`).
- Objetos de valor inmutables como `record` con validación en el constructor compacto.
- Importes y tasas con `BigDecimal`; prohibido `double`/`float` en dominio y aplicación.
- Complejidad ciclomática máxima de 10 por método; métodos cortos con nombres del lenguaje del negocio.
- Sin `GO TO` simulados con banderas: usar retorno temprano, Specification o una cadena de validaciones.
- Errores de negocio como excepciones de dominio con código (por ejemplo `E01`), nunca `catch` vacíos ni
  `printStackTrace`. El adaptador traduce la excepción al formato de salida del contrato.
- Cada clase o método que implementa una regla lleva un comentario de trazabilidad con el formato:
  `// Regla R-03 · AMORTIZA.cbl:112-118`.
- Sin secretos, credenciales ni datos personales en el código.
