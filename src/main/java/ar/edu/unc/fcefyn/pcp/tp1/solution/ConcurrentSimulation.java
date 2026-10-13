package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.Simulation;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Punto de entrada obligatorio. Usa exclusivamente la configuracion recibida.
 */
public final class ConcurrentSimulation implements Simulation {

    @Override
    public SimulationResult execute(SimulationConfig config) throws InterruptedException {
        long startNanos = System.nanoTime();
        Farm farm = new Farm(config, startNanos);

        // 1. Arrancar todas las etapas juntas y esperar a que terminen
        farm.startConcurrentStages();
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        // 2. Si algun hilo aborto la simulacion, se informa el error
        Throwable failure = farm.getFailure();
        if (failure != null) {
            throw new IllegalStateException("La simulacion fue abortada", failure);
        }

        // 3. Verificar invariantes de cierre
        farm.verifyFinalInvariants();

        // 4. Construir resultado y escribir los archivos
        SimulationResult result = farm.buildResult(durationMillis);
        try {
            ResultWriter.write(config.outputDirectory(), farm.eventLines(), result);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron escribir los resultados", e);
        }
        return result;
    }
}
