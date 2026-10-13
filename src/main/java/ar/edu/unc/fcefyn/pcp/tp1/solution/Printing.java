package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;

/**
 * Etapa 3: impresion.
 * READY_TO_PRINT -> PRINTED (libera la impresora y sigue)
 *                o PRINT_FAILED (impresora OUT_OF_SERVICE, la orden termina).
 */
final class Printing extends ConcurrentStage {

    Printing(Farm farm, SimulationConfig config, StageCounter stageCounter) {
        super(farm, config, config.printingDelayMillis(),
                farm.getReadyToPrint(), stageCounter,
                farm.getPrinted(), config.qualityControlThreads());
    }

    @Override
    protected boolean process(Order order) {
        boolean successful = OutcomeDecider.isPrintSuccessful(order.getId(), config);
        return farm.finishPrinting(order, successful);
    }
}
