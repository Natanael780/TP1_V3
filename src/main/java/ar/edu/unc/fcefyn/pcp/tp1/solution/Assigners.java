package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;

/**
 * Etapa 1: asignacion de impresora.
 * Orden CREATED -> WAITING_VALIDATION, impresora AVAILABLE -> RESERVED.
 */
final class Assigners extends ConcurrentStage {

    Assigners(Farm farm, SimulationConfig config, StageCounter stageCounter) {
        super(farm, config, config.assignmentDelayMillis(),
                farm.getCreatedOrders(), stageCounter,
                farm.getWaitingValidation(), config.validationThreads());
    }

    @Override
    protected boolean process(Order order) throws InterruptedException {
        // Bloquea en el semaforo del pool si no hay impresoras libres.
        Printer printer = farm.getPrinterPool().take();
        farm.assign(order, printer);
        return true;
    }
}
