# CodePhoenix — Guion de demo (10 minutos)

Narrativa orientada al valor de negocio. Cada bloque indica **qué mostrar** y **qué decir**.

## Preparación (antes de entrar a la sala)

- [ ] Docker Desktop en marcha y CodePhoenix arriba (`docker compose up -d`); barra superior en verde con
      *Oracle listo*.
- [ ] Una migración COBOL y una PL/SQL **completadas** visibles en *Migraciones anteriores* (respaldo si falla la
      conexión).
- [ ] Un paquete ya descargado y descomprimido, con su imagen construida (`docker build -t migracion .`).
- [ ] Navegador a pantalla completa, zoom al 110 %, pestañas sobrantes cerradas.
- [ ] Grabación de pantalla de una ejecución completa a mano (plan de contingencia final).

## 0:00 – 1:00 · El problema

**Mostrar:** la pantalla de inicio con `AMORTIZA.cbl` cargado.

**Decir:** "Este programa calcula las cuotas de los créditos de consumo. Tiene 25 años de parches, usa GO TO,
decimales empaquetados y hasta una contraseña olvidada en un comentario. Nadie en el banco sabe ya todas las
reglas que contiene. Entenderlo a mano lleva semanas; equivocarse en un centavo cuesta dinero."

**Acción:** pulsar *Iniciar modernización*.

## 1:00 – 2:30 · Seguridad y análisis

**Mostrar:** pestaña *Seguridad y auditoría* → hallazgos de enmascaramiento. Luego *Análisis* → grafo con el
código muerto en rojo y el mapeo de tipos.

**Decir:** "Antes de que la IA vea una sola línea, CodePhoenix oculta la contraseña, el usuario, la conexión y el
correo. El análisis estructural es determinista: no depende de la IA. Ya detectó un párrafo de cálculo de mora
que nadie invoca desde 2009: no lo vamos a migrar."

## 2:30 – 4:30 · Reglas y aprobación humana

**Mostrar:** pestaña *Reglas*. Seleccionar la regla del factor de capitalización (truncamiento por iteración) y
mostrar sus líneas resaltadas. Abrir la tabla de decisión de tramos de score.

**Decir:** "Estas son las reglas de negocio, en español, cada una con su ubicación exacta y su nivel de confianza.
Esta es una regla tácita: el sistema trunca a 10 decimales en cada iteración. Ningún documento lo decía, pero
cambia el centavo final. Y aquí la política de tasas por score, como tabla de decisión para negocio."

**Acción:** ajustar una regla escribiendo un comentario (mostrar que queda registrado), aprobar las demás y
pulsar *Aprobar especificación*.

**Decir:** "Ninguna regla llega al código sin la firma de un experto."

## 4:30 – 7:00 · Generación y prueba de equivalencia

**Mostrar:** la barra de fases avanzando y la actividad de los agentes. Mientras trabaja (o en la migración ya
completada), abrir *Legacy vs. moderno* y seleccionar una regla.

**Decir:** "Seis agentes especializados trabajan en cadena: el arquitecto diseña con arquitectura hexagonal, el
desarrollador escribe el código y se corrige solo con los errores del compilador y del análisis de seguridad."

**Mostrar:** pestaña *Verificación* → tabla Golden Master; expandir un caso para ver ambas salidas lado a lado.

**Decir:** "Aquí está la diferencia: no le pedimos que confíe en la IA. Ejecutamos el COBOL original, de verdad,
y el Java nuevo, con los mismos casos. Idénticos. Además hay más de cien pruebas unitarias, escenarios en
lenguaje de negocio que se ejecutan como pruebas, cobertura sobre el 95 % y cero vulnerabilidades críticas."

**Opcional (si hay tiempo):** mostrar el caso de 360 meses: "el copiloto encontró que en este extremo el sistema
antiguo muestra un saldo negativo como positivo; lo reproducimos para ser equivalentes y lo documentamos."

## 7:00 – 8:30 · PL/SQL y entrega

**Mostrar:** abrir la migración PL/SQL completada desde *Migraciones anteriores* → *Verificación*.

**Decir:** "Lo mismo para PL/SQL, contra una base de datos Oracle real. Aquí comparamos además cómo quedan las
tablas después de liquidar cada siniestro."

**Mostrar:** pestaña *Entrega* → informe ejecutivo y botón de descarga. En una terminal, el paquete ya construido:
`echo "000100000000180000012700" | docker run -i --rm migracion`.

**Decir:** "El resultado es un paquete listo: código, pruebas, reglas, trazabilidad, decisiones de arquitectura,
reportes de seguridad e informe ejecutivo. Se construye y se prueba con un solo comando."

## 8:30 – 10:00 · Valor y cierre

**Mostrar:** pestaña *Tablero* → comparación con el proceso manual y KPIs.

**Decir:** "Lo que hoy cuesta semanas de expertos escasos, CodePhoenix lo hace en minutos con revisión humana,
con evidencia verificable y auditoría completa del uso de IA. Convertimos la caja negra en conocimiento
explícito y en código moderno demostrablemente equivalente."

## Preguntas frecuentes del jurado

| Pregunta | Respuesta corta |
|---|---|
| ¿Y si la IA se equivoca? | El código no se entrega si no es idéntico al original en todos los casos; además un experto aprueba las reglas. |
| ¿Qué datos ve la IA? | Solo el código enmascarado y datos sintéticos; queda registro de cada consulta. |
| ¿Cómo eligen los casos de prueba? | El agente QA los diseña a partir de las reglas, cubriendo fronteras y errores; el usuario puede añadir los suyos. |
| ¿Escala a programas grandes? | El flujo es por programa; la limitación actual es un artefacto por migración (sin copybooks). |
| ¿Por qué no SonarQube? | Usamos SpotBugs + FindSecBugs dentro del sandbox sin red; SonarQube requiere un servidor. |
| ¿Cuánto cuesta cada migración? | La auditoría muestra los tokens consumidos por agente; ~100–150 mil tokens por programa de ejemplo. |

## Plan de contingencia

| Falla | Qué hacer |
|---|---|
| Sin Internet o la API no responde | Abrir las migraciones completadas desde *Migraciones anteriores*: todo funciona sin IA. |
| Oracle no arranca | Presentar la migración PL/SQL ya completada. |
| El portátil falla | Reproducir la grabación de pantalla. |
