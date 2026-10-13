package ar.edu.unc.fcefyn.pcp.tp1.grouptests;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;
import ar.edu.unc.fcefyn.pcp.tp1.solution.ConcurrentSimulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests del grupo: invariantes bajo estres, interrupcion y agotamiento de impresoras. */
class ConcurrencyInvariantsTest {

    @TempDir
    Path outputDirectory;

    /** Muchas ejecuciones con configuraciones aleatorias y demoras nulas (maxima contencion). */
    @Test
    void invariantsHoldOverManyRandomConfigurations() {
        Random random = new Random(42);
        for (int run = 0; run < 150; run++) {
            int orders = 1 + random.nextInt(80);
            long seed = random.nextLong();
            int validPct = random.nextInt(101);
            int printPct = random.nextInt(101);
            int qualityPct = random.nextInt(101);
            SimulationConfig base = config(orders, 1, 1, 1 + random.nextInt(4), 1 + random.nextInt(4),
                    1 + random.nextInt(4), 1 + random.nextInt(4), validPct, printPct, qualityPct,
                    0, 0, 0, 0, seed);
            // Impresoras suficientes: mas que las ordenes que fallaran al imprimir (garantia del enunciado)
            // pero, la mitad de las veces, muy pocas libres a la vez para forzar esperas.
            int failures = 0;
            for (int id = 1; id <= orders; id++) {
                if (OutcomeDecider.isModelValid(id, base) && !OutcomeDecider.isPrintSuccessful(id, base)) {
                    failures++;
                }
            }
            int printers = failures + 1 + (random.nextBoolean() ? 0 : random.nextInt(5));
            int rows = 1 + random.nextInt(Math.min(printers, 4));
            int columns = (printers + rows - 1) / rows;
            SimulationConfig config = config(orders, rows, columns, base.assignmentThreads(),
                    base.validationThreads(), base.printingThreads(), base.qualityControlThreads(),
                    validPct, printPct, qualityPct, 0, 0, 0, 0, seed);
            runAndCheck(config);
        }
    }

    /** Con pocas impresoras y demoras no nulas se fuerzan solapamientos reales entre etapas. */
    @Test
    void invariantsHoldWithSmallDelaysAndScarcePrinters() {
        for (int run = 0; run < 5; run++) {
            runAndCheck(config(60, 1, 3, 3, 2, 3, 2, 100, 100, 90, 1, 2, 2, 1, 1000L + run));
        }
    }

    /** El ejemplo del enunciado en chico: una sola impresora compartida entre muchos hilos. */
    @Test
    void singlePrinterIsSharedWithoutOverlap() {
        for (int run = 0; run < 30; run++) {
            SimulationResult result = runAndCheck(
                    config(25, 1, 1, 4, 4, 4, 4, 100, 100, 100, 0, 0, 0, 0, run));
            assertEquals(25, result.printers().getFirst().usageCount());
        }
    }

    /** Un hilo interrumpido durante la ejecucion debe dejar el sistema sin hilos vivos. */
    @Test
    void interruptionStopsEveryWorkerAndPropagates() throws Exception {
        SimulationConfig config = config(500, 10, 20, 3, 2, 3, 2, 85, 90, 95, 20, 20, 20, 20, 7L);
        Throwable[] thrown = new Throwable[1];
        Thread caller = new Thread(() -> {
            try {
                new ConcurrentSimulation().execute(config);
            } catch (Throwable t) {
                thrown[0] = t;
            }
        }, "caller");
        caller.start();
        Thread.sleep(150);
        caller.interrupt();
        caller.join(5000);

        assertFalse(caller.isAlive());
        assertTrue(thrown[0] instanceof InterruptedException);
        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().matches("(assignment|validation|printing|quality-control)-\\d+"))
                .count();
        assertEquals(0, alive);
    }

    /** Fuera de la garantia del enunciado: si las impresoras se agotan no debe colgarse. */
    @Test
    void exhaustedPrintersAbortInsteadOfHanging() {
        SimulationConfig config = config(10, 1, 1, 2, 2, 2, 2, 100, 0, 100, 0, 0, 0, 0, 5L);
        Object outcome = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try {
                new ConcurrentSimulation().execute(config);
                return "completed";
            } catch (IllegalStateException expected) {
                return "aborted";
            }
        });
        assertEquals("aborted", outcome);
    }

    // ------------------------------------------------------------------

    private SimulationResult runAndCheck(SimulationConfig config) {
        SimulationResult result = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                new ConcurrentSimulation().execute(config));
        assertEquals(config.totalOrders(), result.processedOrders());
        assertTrue(result.allThreadsTerminated());
        String violation;
        try {
            violation = EventLogReplayChecker.check(config, result, config.outputDirectory());
            assertTrue(Files.isRegularFile(config.outputDirectory().resolve("resumen.properties")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertNull(violation, violation);
        for (OrderState state : OrderState.values()) {
            if (!state.isTerminal()) {
                assertEquals(0, result.totalsByState().get(state).intValue());
            }
        }
        return result;
    }

    private SimulationConfig config(int orders, int rows, int columns, int a, int v, int p, int q,
                                    int validPct, int printPct, int qualityPct,
                                    long da, long dv, long dp, long dq, long seed) {
        return new SimulationConfig(orders, rows, columns, a, v, p, q, validPct, printPct, qualityPct,
                da, dv, dp, dq, seed, outputDirectory);
    }
}
