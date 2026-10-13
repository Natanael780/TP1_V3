package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;

/**
 * Orden de impresion.
 *
 * No tiene sincronizacion propia: su estado mutable (state, printer y contadores)
 * se lee y se modifica SOLO dentro de la seccion critica de {@link Farm}
 * (stateLock). El id es final, por eso puede leerse sin lock.
 */
final class Order {

    private final int id;
    private OrderState state = OrderState.CREATED;
    /** Impresora usada; se conserva como dato historico aunque ya se haya liberado. */
    private Printer printer;
    private int assignmentCount;
    private int validationCount;
    private int printingCount;
    private int qualityControlCount;

    Order(int id) {
        this.id = id;
    }

    int getId() {
        return id;
    }

    OrderState getState() {
        return state;
    }

    Printer getPrinter() {
        return printer;
    }

    String getPrinterId() {
        return printer == null ? null : printer.getId();
    }

    void setState(OrderState newState) {
        this.state = newState;
    }

    void assignTo(Printer assigned) {
        this.printer = assigned;
        this.assignmentCount++;
    }

    void recordValidation() {
        validationCount++;
    }

    void recordPrinting() {
        printingCount++;
    }

    void recordQualityControl() {
        qualityControlCount++;
    }

    int getAssignmentCount() {
        return assignmentCount;
    }

    OrderSnapshot getOrderSnapshot() {
        return new OrderSnapshot(id, state, getPrinterId(),
                assignmentCount, validationCount, printingCount, qualityControlCount);
    }
}
