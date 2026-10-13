package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Granja de impresion: estado compartido de la simulacion. Solucion SIN monitores
 * (no usa synchronized ni wait/notify), solo semaforos y locks.
 *
 * <h2>Recursos compartidos y quien los protege</h2>
 * <ul>
 *   <li>Estado de cada {@link Order} y {@link Printer}, el {@link Logger} y los
 *       contadores de esta clase: {@code stateLock}.</li>
 *   <li>Colas entre etapas: cada {@link StageBuffer} (mutex + semaforo de items).</li>
 *   <li>Impresoras libres: {@link PrinterPool} (mutex + semaforo contador).</li>
 *   <li>Hilos vivos por etapa: {@link StageCounter}.</li>
 * </ul>
 *
 * <h2>Por que un unico stateLock para las transiciones</h2>
 * Una transicion modifica la orden, a veces la impresora, y escribe una fila del
 * log. Si eso no fuera atomico otro hilo podria ver, por ejemplo, una orden
 * REJECTED que todavia tiene su impresora RESERVED (viola el invariante 6), o
 * el log quedaria en un orden distinto al de las transiciones. Las secciones
 * criticas son muy cortas (solo asignaciones en memoria), y las demoras y
 * OutcomeDecider quedan FUERA del lock, por lo que las etapas siguen trabajando
 * en paralelo.
 */
final class Farm {

    private final SimulationConfig config;
    private final List<Order> orders = new ArrayList<>();
    private final List<Printer> printers = new ArrayList<>();

    private final StageBuffer createdOrders = new StageBuffer();
    private final StageBuffer waitingValidation = new StageBuffer();
    private final StageBuffer readyToPrint = new StageBuffer();
    private final StageBuffer printed = new StageBuffer();
    private final PrinterPool printerPool;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final Logger eventLogger;
    private final List<Thread> threads = new ArrayList<>();

    // ---- protegidos por stateLock ----
    private int assignedOrders;
    private int operativePrinters;
    private int terminalOrders;
    private Throwable failure;

    Farm(SimulationConfig config, long startNanos) {
        this.config = config;
        this.eventLogger = new Logger(startNanos);

        for (int row = 0; row < config.printerRows(); row++) {
            for (int column = 0; column < config.printerColumns(); column++) {
                printers.add(new Printer(row, column));
            }
        }
        operativePrinters = printers.size();
        printerPool = new PrinterPool(printers);

        // Todas las ordenes nacen CREATED y se encolan para la etapa 1. La creacion
        // ocurre en el hilo main antes de lanzar los workers (Thread.start garantiza
        // que los workers ven todo lo escrito aca).
        for (int id = 1; id <= config.totalOrders(); id++) {
            Order order = new Order(id);
            orders.add(order);
            eventLogger.record(id, Stage.INITIALIZATION, Logger.ORDER_CREATED,
                    null, OrderState.CREATED, null);
            createdOrders.put(order);
        }
        // Ya no se crean mas ordenes: una pildora por cada hilo de asignacion.
        createdOrders.close(config.assignmentThreads());

        createThreads();
    }

    private void createThreads() {
        StageCounter assignment = new StageCounter(config.assignmentThreads());
        StageCounter validation = new StageCounter(config.validationThreads());
        StageCounter printing = new StageCounter(config.printingThreads());
        StageCounter quality = new StageCounter(config.qualityControlThreads());

        for (int i = 1; i <= config.assignmentThreads(); i++) {
            threads.add(new Thread(new Assigners(this, config, assignment), "assignment-" + i));
        }
        for (int i = 1; i <= config.validationThreads(); i++) {
            threads.add(new Thread(new Validation(this, config, validation), "validation-" + i));
        }
        for (int i = 1; i <= config.printingThreads(); i++) {
            threads.add(new Thread(new Printing(this, config, printing), "printing-" + i));
        }
        for (int i = 1; i <= config.qualityControlThreads(); i++) {
            threads.add(new Thread(new QualityAprover(this, config, quality), "quality-control-" + i));
        }
    }

    // ------------------------------------------------------------------
    // Arranque y terminacion
    // ------------------------------------------------------------------

    /**
     * Lanza TODAS las etapas a la vez y espera a que terminen (join).
     * Si el hilo que llama es interrumpido, aborta, espera igual a que todos los
     * workers terminen (para no dejar hilos vivos) y propaga la interrupcion.
     */
    void startConcurrentStages() throws InterruptedException {
        for (Thread thread : threads) {
            thread.start();
        }
        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException interrupted) {
            abort(interrupted);
            for (Thread thread : threads) {
                joinUninterruptibly(thread);
            }
            throw interrupted;
        }
    }

    private static void joinUninterruptibly(Thread thread) {
        while (thread.isAlive()) {
            try {
                thread.join();          // bloqueante, no es espera activa
            } catch (InterruptedException ignored) {
                // ya estamos abortando: seguimos esperando a que el hilo termine
            }
        }
    }

    boolean allThreadsTerminated() {
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Aborta la simulacion: guarda la primera causa e interrumpe a todos los
     * workers. Un hilo bloqueado en Semaphore.acquire() o en Thread.sleep() recibe
     * InterruptedException y termina. Es idempotente.
     */
    void abort(Throwable cause) {
        stateLock.lock();
        try {
            if (failure == null) {
                failure = cause;
            }
        } finally {
            stateLock.unlock();
        }
        for (Thread thread : threads) {
            thread.interrupt();
        }
    }

    Throwable getFailure() {
        stateLock.lock();
        try {
            return failure;
        } finally {
            stateLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Transiciones (cada una es atomica bajo stateLock)
    // ------------------------------------------------------------------

    /** Etapa 1: CREATED -> WAITING_VALIDATION y AVAILABLE -> RESERVED. */
    void assign(Order order, Printer printer) {
        stateLock.lock();
        try {
            printer.reserveFor(order);       // RESERVED + contador de usos
            order.assignTo(printer);         // asocia y cuenta la asignacion
            assignedOrders++;
            transition(order, OrderState.CREATED, OrderState.WAITING_VALIDATION, Stage.ASSIGNMENT);
        } finally {
            stateLock.unlock();
        }
    }

    /** Etapa 2. @return {@code true} si la orden pasa a impresion. */
    boolean finishValidation(Order order, boolean valid) {
        Printer toRelease = null;
        stateLock.lock();
        try {
            order.recordValidation();
            if (valid) {
                transition(order, OrderState.WAITING_VALIDATION, OrderState.READY_TO_PRINT, Stage.VALIDATION);
            } else {
                // Orden terminal e impresora liberada en la MISMA seccion critica.
                order.getPrinter().release();
                transition(order, OrderState.WAITING_VALIDATION, OrderState.REJECTED, Stage.VALIDATION);
                terminalOrders++;
                toRelease = order.getPrinter();
            }
        } finally {
            stateLock.unlock();
        }
        if (toRelease != null) {
            printerPool.giveBack(toRelease);    // despierta a un asignador
        }
        return valid;
    }

    /** Etapa 3. @return {@code true} si la orden pasa a control de calidad. */
    boolean finishPrinting(Order order, boolean successful) {
        boolean exhausted = false;
        stateLock.lock();
        try {
            order.recordPrinting();
            if (successful) {
                order.getPrinter().release();
                transition(order, OrderState.READY_TO_PRINT, OrderState.PRINTED, Stage.PRINTING);
            } else {
                order.getPrinter().putOutOfService();   // nunca vuelve al pool
                transition(order, OrderState.READY_TO_PRINT, OrderState.PRINT_FAILED, Stage.PRINTING);
                terminalOrders++;
                operativePrinters--;
                // Caso fuera de la garantia del enunciado: si no queda ninguna
                // impresora operativa y faltan ordenes por asignar, los asignadores
                // quedarian bloqueados para siempre. Se aborta en vez de colgarse.
                exhausted = operativePrinters == 0 && assignedOrders < config.totalOrders();
            }
        } finally {
            stateLock.unlock();
        }
        if (successful) {
            printerPool.giveBack(order.getPrinter());
        }
        if (exhausted) {
            abort(new IllegalStateException("No quedan impresoras operativas"));
        }
        return successful;
    }

    /** Etapa 4: PRINTED -> APPROVED | DEFECTIVE. */
    void finishQualityControl(Order order, boolean approved) {
        stateLock.lock();
        try {
            order.recordQualityControl();
            transition(order, OrderState.PRINTED,
                    approved ? OrderState.APPROVED : OrderState.DEFECTIVE, Stage.QUALITY_CONTROL);
            terminalOrders++;
        } finally {
            stateLock.unlock();
        }
    }

    /** Debe llamarse con stateLock tomado. Verifica el estado de origen y registra el evento. */
    private void transition(Order order, OrderState from, OrderState to, Stage stage) {
        if (order.getState() != from) {
            throw new IllegalStateException("Orden " + order.getId() + " en " + order.getState()
                    + ", se esperaba " + from + " para pasar a " + to);
        }
        order.setState(to);
        eventLogger.record(order.getId(), stage, Logger.ORDER_STATE_CHANGED,
                from, to, order.getPrinterId());
    }

    // ------------------------------------------------------------------
    // Resultado (se llama con todos los workers ya terminados)
    // ------------------------------------------------------------------

    /** Invariantes de cierre: 8, 9, 10 y registros intermedios vacios. */
    void verifyFinalInvariants() {
        stateLock.lock();
        try {
            int uses = 0;
            int assignments = 0;
            for (Order order : orders) {
                if (!order.getState().isTerminal()) {
                    throw new IllegalStateException("Orden " + order.getId()
                            + " quedo en estado intermedio " + order.getState());
                }
                assignments += order.getAssignmentCount();
            }
            for (Printer printer : printers) {
                if (printer.getState() == PrinterState.RESERVED || printer.getAssignedOrderId() != null) {
                    throw new IllegalStateException("Impresora " + printer.getId() + " quedo reservada");
                }
                uses += printer.getUsageCount();
            }
            if (uses != assignments) {
                throw new IllegalStateException("Usos de impresoras (" + uses
                        + ") distinto de asignaciones (" + assignments + ")");
            }
            if (createdOrders.pendingOrders() + waitingValidation.pendingOrders()
                    + readyToPrint.pendingOrders() + printed.pendingOrders() != 0) {
                throw new IllegalStateException("Quedaron ordenes en registros intermedios");
            }
        } finally {
            stateLock.unlock();
        }
    }

    SimulationResult buildResult(long durationMillis) {
        stateLock.lock();
        try {
            List<OrderSnapshot> orderSnapshots = new ArrayList<>(orders.size());
            Map<OrderState, Integer> totals = new EnumMap<>(OrderState.class);
            for (OrderState state : OrderState.values()) {
                totals.put(state, 0);
            }
            for (Order order : orders) {
                orderSnapshots.add(order.getOrderSnapshot());
                totals.merge(order.getState(), 1, Integer::sum);
            }
            List<PrinterSnapshot> printerSnapshots = new ArrayList<>(printers.size());
            for (Printer printer : printers) {
                printerSnapshots.add(printer.getPrinterSnapshot());
            }
            return new SimulationResult(config.totalOrders(), terminalOrders, totals,
                    orderSnapshots, printerSnapshots, durationMillis, allThreadsTerminated());
        } finally {
            stateLock.unlock();
        }
    }

    List<String> eventLines() {
        stateLock.lock();
        try {
            return eventLogger.lines();
        } finally {
            stateLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Acceso a los recursos compartidos para los workers
    // ------------------------------------------------------------------

    StageBuffer getCreatedOrders() {
        return createdOrders;
    }

    StageBuffer getWaitingValidation() {
        return waitingValidation;
    }

    StageBuffer getReadyToPrint() {
        return readyToPrint;
    }

    StageBuffer getPrinted() {
        return printed;
    }

    PrinterPool getPrinterPool() {
        return printerPool;
    }
}
