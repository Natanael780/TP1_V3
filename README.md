# TP1 Programacion Concurrente y Paralela

Repositorio plantilla del Trabajo Practico 1 sobre una granja concurrente de impresion 3D.

## Requisitos

- Java 21.
- Maven 3.9 o compatible.
- No se admiten dependencias de produccion externas a la biblioteca estandar de Java.
- De `java.util.concurrent` solo se permiten `Semaphore`, `Lock`, `ReentrantLock`,
  `Executor`, `ExecutorService`, `Executors` y `ThreadPoolExecutor`.
- El uso de ejecutores es opcional; `Thread` es el camino recomendado. Si se usan,
  deben limitarse a ejecutar los workers y esperar su finalizacion.

## Comandos

Ejecutar la suite publica:

```bash
mvn clean test
```

Construir el proyecto:

```bash
mvn clean package
```

Ejecutar la configuracion ubicada en `config/tp1.properties`:

```bash
java -jar target/tp1.jar
```

Los resultados se escriben en `resultados/` (`eventos.csv`, `elementos.csv`, `resumen.properties`).

## Arquitectura (solucion con semaforos, sin monitores)

No se usa `synchronized` ni `wait/notify`. Solo `Semaphore` y `ReentrantLock`.

```
created ──> [Assigners x3] ──> waitingValidation ──> [Validation x2] ──> readyToPrint ──> [Printing x3] ──> printed ──> [QualityAprover x2]
                 │  ▲                                     │                                    │
                 ▼  │ giveBack                            │ REJECTED: giveBack                 │ PRINTED: giveBack
             PrinterPool  <───────────────────────────────┴────────────────────────────────────┘ (PRINT_FAILED: nunca vuelve)
```

| Clase | Rol | Sincronizacion |
|---|---|---|
| `StageBuffer` | Cola entre etapas (productor-consumidor) | `ReentrantLock` mutex + `Semaphore items(0)` |
| `PrinterPool` | Impresoras libres | `ReentrantLock` mutex + `Semaphore available(N)` |
| `Farm` | Estado de ordenes/impresoras, log y transiciones | `ReentrantLock stateLock` |
| `StageCounter` | Hilos vivos por etapa (el ultimo cierra la siguiente) | `ReentrantLock` |
| `ConcurrentStage` | Ciclo comun de los workers (template method) | — |
| `Assigners`, `Validation`, `Printing`, `QualityAprover` | Las cuatro etapas | — |

Terminacion: cada buffer se cierra con una *poison pill* por hilo consumidor cuando
termina el ultimo hilo de la etapa productora. Los hilos terminan solos y `main`
los espera con `join()`.

## Documentacion

El enunciado completo se encuentra en [ENUNCIADO.md](ENUNCIADO.md). Los diagramas y el informe final deben ubicarse en `docs/` con los nombres indicados en ese documento.

## Entrega

La entrega se identifica mediante el tag Git `{nombre-del-grupo}-entrega-tp1`. El ZIP que se sube al aula virtual debe generarse desde ese mismo tag.
