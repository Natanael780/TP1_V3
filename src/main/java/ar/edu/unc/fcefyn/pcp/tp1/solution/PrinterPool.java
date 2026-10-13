package ar.edu.unc.fcefyn.pcp.tp1.solution;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Conjunto de impresoras libres (recurso compartido con N unidades).
 *
 * <ul>
 *   <li>{@code available} (Semaphore, arranca en totalPrinters): cuenta las
 *       impresoras libres. Un hilo de asignacion hace acquire y, si no hay
 *       ninguna, queda bloqueado hasta que otra etapa libere una.</li>
 *   <li>{@code mutex}: protege la cola de impresoras libres.</li>
 * </ul>
 *
 * Una impresora que pasa a OUT_OF_SERVICE simplemente nunca se devuelve: el
 * permiso del semaforo se pierde con ella, y asi no puede reutilizarse.
 */
final class PrinterPool {

    private final ArrayDeque<Printer> free = new ArrayDeque<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Semaphore available;

    PrinterPool(List<Printer> printers) {
        free.addAll(printers);
        available = new Semaphore(printers.size());
    }

    /** Espera (bloqueado) hasta que haya una impresora libre y la retira del pool. */
    Printer take() throws InterruptedException {
        available.acquire();
        mutex.lock();
        try {
            return free.pollFirst();
        } finally {
            mutex.unlock();
        }
    }

    /** Devuelve una impresora que volvio a AVAILABLE y despierta a un asignador. */
    void giveBack(Printer printer) {
        mutex.lock();
        try {
            free.addLast(printer);
        } finally {
            mutex.unlock();
        }
        available.release();
    }
}
