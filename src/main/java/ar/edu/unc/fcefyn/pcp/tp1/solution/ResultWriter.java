package ar.edu.unc.fcefyn.pcp.tp1.solution;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Escribe eventos.csv, elementos.csv y resumen.properties. Se ejecuta en el hilo
 * main cuando todos los workers ya terminaron; los try-with-resources garantizan
 * que los archivos quedan cerrados antes de retornar.
 */
final class ResultWriter {

    private ResultWriter() {
    }

    static void write(Path directory, List<String> events, SimulationResult result) throws IOException {
        Files.createDirectories(directory);

        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("eventos.csv"),
                StandardCharsets.UTF_8)) {
            out.write(Logger.HEADER);
            out.newLine();
            for (String line : events) {
                out.write(line);
                out.newLine();
            }
        }

        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("elementos.csv"),
                StandardCharsets.UTF_8)) {
            out.write("orderId;finalState;printer;assignmentCount;validationCount;"
                    + "printingCount;qualityControlCount");
            out.newLine();
            for (OrderSnapshot order : result.orders()) {   // ya vienen ordenadas por id
                out.write(order.id() + ";" + order.state() + ";"
                        + (order.assignedPrinterId() == null ? "" : order.assignedPrinterId()) + ";"
                        + order.assignmentCount() + ";" + order.validationCount() + ";"
                        + order.printingCount() + ";" + order.qualityControlCount());
                out.newLine();
            }
        }

        Map<OrderState, Integer> totals = result.totalsByState();
        int remainingIntermediate = 0;
        for (Map.Entry<OrderState, Integer> entry : totals.entrySet()) {
            if (!entry.getKey().isTerminal()) {
                remainingIntermediate += entry.getValue();
            }
        }

        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("resumen.properties"),
                StandardCharsets.UTF_8)) {
            String[] lines = {
                    "totalOrders=" + result.totalOrders(),
                    "processedOrders=" + result.processedOrders(),
                    "approvedOrders=" + totals.get(OrderState.APPROVED),
                    "rejectedOrders=" + totals.get(OrderState.REJECTED),
                    "printFailedOrders=" + totals.get(OrderState.PRINT_FAILED),
                    "defectiveOrders=" + totals.get(OrderState.DEFECTIVE),
                    "durationMillis=" + result.durationMillis(),
                    "allThreadsTerminated=" + result.allThreadsTerminated(),
                    "remainingIntermediateOrders=" + remainingIntermediate
            };
            for (String line : lines) {
                out.write(line);
                out.newLine();
            }
        }
    }
}
