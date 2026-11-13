# CodePhoenix — Instalación y herramientas

## 1. Herramientas

| Herramienta | Versión | Para qué | ¿Obligatoria? |
|---|---|---|---|
| Docker Desktop | 4.x (motor 27+) | Sandboxes y, opcionalmente, CodePhoenix completo | Sí |
| Clave de la API de Anthropic | — | Agentes de IA (modelo Claude Sonnet 5.5) | Sí |
| JDK | 21 | Ejecutar CodePhoenix sin Docker y las pruebas del proyecto | Solo en modo desarrollo |
| Git | 2.x | Control de versiones | Recomendada |
| Navegador | Chrome, Edge o Firefox recientes | Portal web | Sí |

### Dentro de los sandboxes (se instalan solas al construir las imágenes)

| Componente | Uso |
|---|---|
| GnuCOBOL 3 | Compilar y ejecutar el COBOL original |
| Oracle Database 23ai Free (`gvenzl/oracle-free:23-slim-faststart`) | Compilar y ejecutar el PL/SQL original |
| Eclipse Temurin JDK 21 | Compilar y ejecutar el Java generado |
| JUnit Platform 1.11 | Pruebas unitarias |
| JaCoCo 0.8.12 | Cobertura |
| SpotBugs 4.8.6 + FindSecBugs 1.13.0 | Análisis de seguridad (SAST) |
| Cucumber 7.20 | Escenarios Gherkin ejecutables |

### Bibliotecas de CodePhoenix

Spring Boot 4.1, Spring AI 2.0 (Anthropic), Jackson 3. En el navegador: marked, DOMPurify y Mermaid (desde CDN).

## 2. Opción A — Todo en Docker (un solo comando)

```bash
# Git Bash / Linux / macOS
export SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...
docker compose up --build -d
```

```powershell
# PowerShell
$env:SPRING_AI_ANTHROPIC_API_KEY = "sk-ant-..."
docker compose up --build -d
```

Abre <http://localhost:8080>. La primera vez tarda varios minutos: construye la aplicación y el sandbox y
descarga Oracle (~6 GB). Oracle tarda además ~1 minuto en arrancar; el indicador de la barra superior pasa
de *Oracle iniciando…* a *Oracle listo*.

Detener: `docker compose down` (las migraciones se conservan en el volumen `codephoenix-workspace`).

## 3. Opción B — Modo desarrollo

```powershell
docker build -t codephoenix-sandbox:2 sandbox
docker pull gvenzl/oracle-free:23-slim-faststart      # solo para PL/SQL
[Environment]::SetEnvironmentVariable("SPRING_AI_ANTHROPIC_API_KEY", "sk-ant-...", "User")   # una vez; reabre la terminal
.\gradlew.bat bootRun
```

Las migraciones se guardan en `.\workspace` (excluido de git).

## 4. Recursos recomendados

| Recurso | Mínimo |
|---|---|
| Memoria asignada a Docker | 6 GB (Oracle usa 2 GB, cada sandbox 1 GB) |
| Disco libre | 15 GB |
| Red | Acceso a `api.anthropic.com` y, la primera vez, a Docker Hub, Maven Central y GitHub |

## 5. Solución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| Barra superior: *Sandbox no disponible* | Docker Desktop apagado o imagen no construida | Inicia Docker Desktop; `docker build -t codephoenix-sandbox:2 sandbox` |
| Barra superior: *sin Oracle* | Falta la imagen de Oracle | `docker pull gvenzl/oracle-free:23-slim-faststart` |
| Fase 2 de PL/SQL tarda ~1 minuto | Oracle arrancando por primera vez | Esperar; las siguientes migraciones lo reutilizan |
| Error `ORA-04021` o Oracle se detiene al arrancar | Falta de memoria o CPU al arrancar | CodePhoenix lo recrea una vez automáticamente; si persiste, cierra otras cargas o aumenta la memoria de Docker |
| Una fase falla con "superó el límite de tokens" | Respuesta del modelo demasiado larga | Reintentar la migración; si se repite, reportarlo con la carpeta `workspace/jobs/<id>/ai-responses` |
| HTTP 401 de Anthropic | Clave inválida o sin crédito | Revisar la clave en console.anthropic.com y el saldo |
| `Port 8080 was already in use` | Otra instancia en marcha | Detenerla o usar `--server.port=8081` |
| El paquete devuelve `ERR|E00` al probarlo en PowerShell | PowerShell 5.1 antepone un BOM a la tubería | Usar Git Bash o `cmd` |
| Caracteres extraños (`Ã³`) en archivos | Se editaron con `Get-Content`/`Set-Content` de PowerShell 5.1 | Editar con un editor UTF-8; los archivos del repositorio son UTF-8 con fin de línea LF |

## 6. Limpieza

```bash
docker compose down -v                    # borra también las migraciones guardadas
docker rm -f codephoenix-oracle           # elimina el contenedor de Oracle
docker network rm codephoenix-isolated
```
