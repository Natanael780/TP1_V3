package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;

/**
 * Etapa 4: control de calidad. PRINTED -> APPROVED o DEFECTIVE.
 * No usa impresoras: ya fue liberada en la etapa de impresion.
 * Es la ultima etapa, por eso no tiene buffer de salida.
 */
final class QualityAprover extends ConcurrentStage {

    QualityAprover(Farm farm, SimulationConfig config, StageCounter stageCounter) {
        super(farm, config, config.qualityControlDelayMillis(),
                farm.getPrinted(), stageCounter,
                null, 0);
    }

    @Override
    protected boolean process(Order order) {
        boolean approved = OutcomeDecider.isQualityApproved(order.getId(), config);
        farm.finishQualityControl(order, approved);
        return false;
    }
}
