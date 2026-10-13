package ar.edu.unc.fcefyn.pcp.tp1.solution;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Cuenta los hilos de una etapa que siguen trabajando. Lo comparten todos los
 * hilos de la misma etapa. El ultimo en terminar es el responsable de cerrar el
 * buffer de la etapa siguiente.
 */
final class StageCounter {

    private final ReentrantLock mutex = new ReentrantLock();
    private int activeWorkers;

    StageCounter(int workers) {
        this.activeWorkers = workers;
    }

    /** @return {@code true} si quien llama es el ultimo hilo de la etapa en terminar */
    boolean workerFinished() {
        mutex.lock();
        try {
            activeWorkers--;
            return activeWorkers == 0;
        } finally {
            mutex.unlock();
        }
    }
}
