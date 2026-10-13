package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;

import java.util.ArrayList;
import java.util.List;

/**
 * Registro en memoria de las filas de eventos.csv.
 *
 * NO es thread-safe a proposito: {@link Farm} lo invoca SIEMPRE con su stateLock
 * tomado, dentro de la misma seccion critica que confirma la transicion. Gracias
 * a eso:
 * <ul>
 *   <li>la secuencia es unica y creciente (nadie mas incrementa {@code sequence}
 *       al mismo tiempo);</li>
 *   <li>el orden de las filas es el orden en que las transiciones quedaron
 *       confirmadas;</li>
 *   <li>elapsedMs nunca decrece de una fila a la siguiente.</li>
 * </ul>
 * Si tuviera su propio lock, separado del de la transicion, dos hilos podrian
 * confirmar A y luego B pero registrar B antes que A.
 */
final class Logger {

    static final String HEADER =
            "sequence;elapsedMs;thread;orderId;stage;event;fromState;toState;printer";
    static final String ORDER_CREATED = "ORDER_CREATED";
    static final String ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED";

    private final long startNanos;
    private final List<String> lines = new ArrayList<>();
    private long sequence;

    Logger(long startNanos) {
        this.startNanos = startNanos;
    }

    void record(int orderId, Stage stage, String event,
                OrderState from, OrderState to, String printerId) {
        sequence++;
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        lines.add(sequence + ";" + elapsedMs + ";" + Thread.currentThread().getName()
                + ";" + orderId + ";" + stage + ";" + event
                + ";" + (from == null ? "" : from)
                + ";" + to
                + ";" + (printerId == null ? "" : printerId));
    }

    List<String> lines() {
        return List.copyOf(lines);
    }
}
