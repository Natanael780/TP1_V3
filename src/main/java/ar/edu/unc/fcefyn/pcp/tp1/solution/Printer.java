package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;

/**
 * Impresora de la matriz.
 *
 * Igual que {@link Order}, no se sincroniza sola: se modifica SOLO dentro de la
 * seccion critica de {@link Farm}. Cada metodo verifica el estado de origen, asi
 * una transicion ilegal (por ejemplo reservar una impresora ya reservada) se
 * detecta en el momento con una excepcion en vez de pasar desapercibida.
 */
final class Printer {

    private final String id;
    private final int row;
    private final int column;
    private PrinterState state = PrinterState.AVAILABLE;
    private int usageCount;
    private Integer assignedOrderId;

    Printer(int row, int column) {
        this.row = row;
        this.column = column;
        this.id = "P-" + row + "-" + column;
    }

    String getId() {
        return id;
    }

    int getRow() {
        return row;
    }

    int getColumn() {
        return column;
    }

    PrinterState getState() {
        return state;
    }

    int getUsageCount() {
        return usageCount;
    }

    Integer getAssignedOrderId() {
        return assignedOrderId;
    }

    /** AVAILABLE -> RESERVED. Incrementa el contador de usos. */
    void reserveFor(Order order) {
        require(PrinterState.AVAILABLE);
        state = PrinterState.RESERVED;
        usageCount++;
        assignedOrderId = order.getId();
    }

    /** RESERVED -> AVAILABLE. */
    void release() {
        require(PrinterState.RESERVED);
        state = PrinterState.AVAILABLE;
        assignedOrderId = null;
    }

    /** RESERVED -> OUT_OF_SERVICE. No vuelve a usarse durante la ejecucion. */
    void putOutOfService() {
        require(PrinterState.RESERVED);
        state = PrinterState.OUT_OF_SERVICE;
        assignedOrderId = null;
    }

    PrinterSnapshot getPrinterSnapshot() {
        return new PrinterSnapshot(id, state, usageCount, assignedOrderId);
    }

    private void require(PrinterState expected) {
        if (state != expected) {
            throw new IllegalStateException("Transicion invalida en " + id
                    + ": estado " + state + ", se esperaba " + expected);
        }
    }
}
