package ar.edu.unc.fcefyn.pcp.tp1.grouptests;

import ar.edu.unc.fcefyn.pcp.tp1.api.OrderSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.OrderState;
import ar.edu.unc.fcefyn.pcp.tp1.api.OutcomeDecider;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterSnapshot;
import ar.edu.unc.fcefyn.pcp.tp1.api.PrinterState;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationConfig;
import ar.edu.unc.fcefyn.pcp.tp1.api.SimulationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verificador independiente de la implementacion: reconstruye el estado de ordenes e
 * impresoras SOLO a partir de eventos.csv (reproduciendolo en orden) y comprueba los
 * invariantes del enunciado. Devuelve la lista de violaciones (vacia si todo esta bien).
 */
final class EventLogReplayChecker {

    private EventLogReplayChecker() {
    }

    static String check(SimulationConfig config, SimulationResult result, Path directory)
            throws IOException {
        List<String> lines = Files.readAllLines(directory.resolve("eventos.csv"));
        if (!lines.getFirst().equals(
                "sequence;elapsedMs;thread;orderId;stage;event;fromState;toState;printer")) {
            return "cabecera invalida";
        }

        Map<Integer, OrderState> orderState = new HashMap<>();
        Map<Integer, String> orderPrinter = new HashMap<>();
        Map<String, Integer> holder = new HashMap<>();        // impresora -> orden que la tiene
        Set<String> outOfService = new HashSet<>();
        Map<String, Integer> usesByPrinter = new HashMap<>();
        long expectedSequence = 1;
        long previousElapsed = 0;

        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(";", -1);
            if (f.length != 9) {
                return "fila mal formada: " + line;
            }
            if (Long.parseLong(f[0]) != expectedSequence++) {
                return "secuencia no consecutiva en: " + line;
            }
            long elapsed = Long.parseLong(f[1]);
            if (elapsed < 0 || elapsed < previousElapsed) {
                return "elapsedMs decreciente o negativo en: " + line;
            }
            previousElapsed = elapsed;

            String thread = f[2];
            int id = Integer.parseInt(f[3]);
            String stage = f[4];
            String event = f[5];
            String printer = f[8];

            if (event.equals("ORDER_CREATED")) {
                if (orderState.containsKey(id) || !f[6].isEmpty() || !f[7].equals("CREATED")
                        || !printer.isEmpty() || !stage.equals("INITIALIZATION")) {
                    return "ORDER_CREATED invalido: " + line;
                }
                orderState.put(id, OrderState.CREATED);
                continue;
            }

            OrderState from = OrderState.valueOf(f[6]);
            OrderState to = OrderState.valueOf(f[7]);
            if (!event.equals("ORDER_STATE_CHANGED") || orderState.get(id) != from) {
                return "transicion fuera de secuencia para la orden " + id + ": " + line;
            }
            orderState.put(id, to);

            String key = from + ">" + to;
            switch (key) {
                case "CREATED>WAITING_VALIDATION" -> {
                    if (!stage.equals("ASSIGNMENT") || !thread.startsWith("assignment-")) {
                        return "etapa/hilo incorrecto: " + line;
                    }
                    if (printer.isEmpty() || holder.containsKey(printer) || outOfService.contains(printer)) {
                        return "impresora " + printer + " asignada estando ocupada o fuera de servicio: " + line;
                    }
                    holder.put(printer, id);
                    orderPrinter.put(id, printer);
                    usesByPrinter.merge(printer, 1, Integer::sum);
                }
                case "WAITING_VALIDATION>READY_TO_PRINT" ->
                        { String e = requireHolder(stage, "VALIDATION", thread, "validation-", printer, id, holder, orderPrinter, line); if (e != null) return e; }
                case "WAITING_VALIDATION>REJECTED", "READY_TO_PRINT>PRINTED" -> {
                    String e = requireHolder(stage, key.startsWith("WAITING") ? "VALIDATION" : "PRINTING",
                            thread, key.startsWith("WAITING") ? "validation-" : "printing-",
                            printer, id, holder, orderPrinter, line);
                    if (e != null) return e;
                    holder.remove(printer);
                }
                case "READY_TO_PRINT>PRINT_FAILED" -> {
                    String e = requireHolder(stage, "PRINTING", thread, "printing-", printer, id, holder, orderPrinter, line);
                    if (e != null) return e;
                    holder.remove(printer);
                    outOfService.add(printer);
                }
                case "PRINTED>APPROVED", "PRINTED>DEFECTIVE" -> {
                    if (!stage.equals("QUALITY_CONTROL") || !thread.startsWith("quality-control-")
                            || !printer.equals(orderPrinter.get(id))) {
                        return "control de calidad invalido: " + line;
                    }
                }
                default -> {
                    return "transicion ilegal " + key + ": " + line;
                }
            }
        }

        // Estado final reconstruido
        if (orderState.size() != config.totalOrders()) {
            return "cantidad de ordenes en el log: " + orderState.size();
        }
        for (int id = 1; id <= config.totalOrders(); id++) {
            OrderState state = orderState.get(id);
            if (state == null || !state.isTerminal()) {
                return "orden " + id + " no termino: " + state;
            }
            OrderState expected = expectedState(id, config);
            if (state != expected) {
                return "orden " + id + " termino en " + state + " y se esperaba " + expected;
            }
        }
        if (!holder.isEmpty()) {
            return "impresoras reservadas al final segun el log: " + holder;
        }

        // Consistencia log <-> resultado devuelto
        for (PrinterSnapshot p : result.printers()) {
            if (p.state() == PrinterState.RESERVED || p.assignedOrderId() != null) {
                return "impresora reservada en el resultado: " + p;
            }
            if (p.usageCount() != usesByPrinter.getOrDefault(p.id(), 0)) {
                return "usageCount de " + p.id() + " (" + p.usageCount() + ") no coincide con el log";
            }
            if ((p.state() == PrinterState.OUT_OF_SERVICE) != outOfService.contains(p.id())) {
                return "estado de " + p.id() + " no coincide con el log";
            }
        }
        for (OrderSnapshot o : result.orders()) {
            if (o.state() != orderState.get(o.id())) {
                return "estado de la orden " + o.id() + " no coincide con el log";
            }
        }
        List<String> elementos = Files.readAllLines(directory.resolve("elementos.csv"));
        if (elementos.size() != config.totalOrders() + 1) {
            return "elementos.csv con filas de mas o de menos";
        }
        return null;
    }

    private static String requireHolder(String stage, String expectedStage, String thread,
                                        String threadPrefix, String printer, int id,
                                        Map<String, Integer> holder, Map<Integer, String> orderPrinter,
                                        String line) {
        if (!stage.equals(expectedStage) || !thread.startsWith(threadPrefix)) {
            return "etapa/hilo incorrecto: " + line;
        }
        if (!printer.equals(orderPrinter.get(id)) || !Integer.valueOf(id).equals(holder.get(printer))) {
            return "la orden " + id + " opera sin tener reservada la impresora " + printer + ": " + line;
        }
        return null;
    }

    static OrderState expectedState(int id, SimulationConfig config) {
        if (!OutcomeDecider.isModelValid(id, config)) {
            return OrderState.REJECTED;
        }
        if (!OutcomeDecider.isPrintSuccessful(id, config)) {
            return OrderState.PRINT_FAILED;
        }
        return OutcomeDecider.isQualityApproved(id, config) ? OrderState.APPROVED : OrderState.DEFECTIVE;
    }
}
