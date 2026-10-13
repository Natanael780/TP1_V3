package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;

/**
 * Etapa 2: validacion del modelo.
 * WAITING_VALIDATION -> READY_TO_PRINT (sigue) o REJECTED (termina y libera la impresora).
 */
final class Validation extends ConcurrentStage {

    Validation(Farm farm, SimulationConfig config, StageCounter stageCounter) {
        super(farm, config, config.validationDelayMillis(),
                farm.getWaitingValidation(), stageCounter,
                farm.getReadyToPrint(), config.printingThreads());
    }

    @Override
    protected boolean process(Order order) {
        // El resultado depende del id de la ORDEN (no de la impresora).
        boolean valid = OutcomeDecider.isModelValid(order.getId(), config);
        return farm.finishValidation(order, valid);
    }
}
