package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;

/**
 * Base de los hilos de cada etapa (patron Template Method).
 *
 * Ciclo de vida de un worker:
 * <pre>
 *   mientras (orden = input.take()) != null      // se bloquea en un semaforo si no hay trabajo
 *       demora de la etapa (Thread.sleep)         // FUERA de cualquier lock
 *       si process(orden) -> output.put(orden)    // pasa a la etapa siguiente
 *   si soy el ultimo hilo de la etapa -> output.close(hilos de la etapa siguiente)
 * </pre>
 *
 * Los hilos terminan solos al recibir la pildora de fin: nunca se usa
 * Thread.stop ni System.exit. Ante una interrupcion o un error se aborta toda la
 * simulacion para que ningun otro hilo quede bloqueado para siempre.
 */
abstract class ConcurrentStage implements Runnable {

    protected final Farm farm;
    protected final SimulationConfig config;
    private final StageBuffer input;
    private final StageBuffer output;
    private final int outputConsumers;
    private final StageCounter stageCounter;
    private final long delayMs;

    ConcurrentStage(Farm farm, SimulationConfig config, long delayMs,
                    StageBuffer input, StageCounter stageCounter,
                    StageBuffer output, int outputConsumers) {
        this.farm = farm;
        this.config = config;
        this.delayMs = delayMs;
        this.input = input;
        this.stageCounter = stageCounter;
        this.output = output;
        this.outputConsumers = outputConsumers;
    }

    /**
     * Procesa una orden de la etapa.
     *
     * @return {@code true} si la orden sigue hacia la etapa siguiente
     */
    protected abstract boolean process(Order order) throws InterruptedException;

    @Override
    public final void run() {
        try {
            Order order;
            while ((order = input.take()) != null) {
                work();
                if (process(order)) {
                    output.put(order);
                }
            }
            // Recibi la pildora. Si soy el ultimo de mi etapa, ya nadie mas va a
            // producir para la etapa siguiente: le aviso que termine.
            if (stageCounter.workerFinished() && output != null) {
                output.close(outputConsumers);
            }
        } catch (InterruptedException e) {
            farm.abort(e);
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            farm.abort(e);
        }
    }

    /** Demora propia de la operacion. Es trabajo simulado, no sincronizacion. */
    private void work() throws InterruptedException {
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }
}
