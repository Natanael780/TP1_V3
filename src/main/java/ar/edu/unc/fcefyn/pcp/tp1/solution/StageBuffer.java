package ar.edu.unc.fcefyn.pcp.tp1.solution;

import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registro intermedio entre dos etapas (productor-consumidor con semaforos).
 *
 * <ul>
 *   <li>{@code mutex} (ReentrantLock): exclusion mutua sobre la cola
 *       {@link ArrayDeque}, que no es thread-safe.</li>
 *   <li>{@code items} (Semaphore, arranca en 0): cantidad de elementos en la cola.
 *       {@code take()} hace acquire y se BLOQUEA (sin espera activa) si no hay nada;
 *       {@code put()} hace release y despierta a un consumidor.</li>
 * </ul>
 *
 * El buffer no tiene limite de capacidad porque la cantidad total de ordenes es
 * finita y conocida; por eso no hace falta el semaforo de "espacios libres" del
 * buffer acotado clasico.
 *
 * <h2>Terminacion: poison pill</h2>
 * Cuando la etapa productora termino, {@link #close(int)} encola una "pildora"
 * por cada hilo consumidor. Como la cola es FIFO y las pildoras se encolan
 * despues de la ultima orden real, cada consumidor recibe primero todo el trabajo
 * pendiente y luego exactamente una pildora, que le indica que debe terminar.
 */
final class StageBuffer {

    /** Marcador de fin de trabajo. Nunca se registra ni se procesa. */
    private static final Order POISON_PILL = new Order(0);

    private final ArrayDeque<Order> queue = new ArrayDeque<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Semaphore items = new Semaphore(0);

    /** Agrega una orden y avisa a un consumidor. */
    void put(Order order) {
        mutex.lock();
        try {
            queue.addLast(order);
        } finally {
            mutex.unlock();
        }
        items.release();
    }

    /**
     * Espera (bloqueado) hasta que haya un elemento y lo retira.
     *
     * @return la orden, o {@code null} si se recibio la pildora (no habra mas trabajo)
     */
    Order take() throws InterruptedException {
        items.acquire();
        Order order;
        mutex.lock();
        try {
            order = queue.pollFirst();
        } finally {
            mutex.unlock();
        }
        return order == POISON_PILL ? null : order;
    }

    /** Encola una pildora por cada consumidor: ya no llegaran mas ordenes. */
    void close(int consumers) {
        for (int i = 0; i < consumers; i++) {
            put(POISON_PILL);
        }
    }

    /** Cantidad de ordenes reales que quedan en la cola (para verificar al final). */
    int pendingOrders() {
        mutex.lock();
        try {
            int pending = 0;
            for (Order order : queue) {
                if (order != POISON_PILL) {
                    pending++;
                }
            }
            return pending;
        } finally {
            mutex.unlock();
        }
    }
}
