package com.pvb.smperf.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU orders for the engine's --cpus option. The engine uses the first N ids for N threads. */
public final class Placement {

    private Placement() {}

    /** Default: one logical CPU per physical core first, then the hyperthread siblings. */
    public static List<Integer> separateCores(MachineInfo m) {
        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (var c : m.cpus()) if (seen.add(c.physicalCore())) out.add(c.id());
        for (var c : m.cpus()) if (!out.contains(c.id())) out.add(c.id());
        return out;
    }

    /** Fill both hyperthreads of a core before moving to the next core. */
    public static List<Integer> sameCoreFirst(MachineInfo m) {
        List<Integer> out = new ArrayList<>();
        Map<Integer, List<Integer>> byCore = new LinkedHashMap<>();
        for (var c : m.cpus())
            byCore.computeIfAbsent(c.physicalCore(), k -> new ArrayList<>()).add(c.id());
        byCore.values().forEach(out::addAll);
        return out;
    }

    /** Performance cores only (every CPU on non-hybrid machines), one thread per core first. */
    public static List<Integer> performanceOnly(MachineInfo m) {
        return separateCores(m).stream()
                .filter(id -> m.cpus().stream()
                        .anyMatch(c -> c.id() == id && !"efficiency".equals(c.type())))
                .toList();
    }
    /** Parses "0,2,4" into a CPU order, checking every id exists and none repeats. */
    public static List<Integer> parseCustom(String text, MachineInfo m) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Enter CPU ids, e.g. 0,2,4.");
        Set<Integer> valid = new HashSet<>();
        for (var c : m.cpus()) valid.add(c.id());

        List<Integer> out = new ArrayList<>();
        for (String s : text.split(",")) {
            int id;
            try {
                id = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CPU ids must be integers separated by commas.");
            }
            if (!valid.contains(id))
                throw new IllegalArgumentException("CPU " + id + " does not exist on this machine.");
            if (out.contains(id))
                throw new IllegalArgumentException("CPU " + id + " is listed twice.");
            out.add(id);
        }
        return out;
    }
    private static Map<Integer, List<Integer>> groupsByCache(MachineInfo m, int level) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int id : separateCores(m)) {
            int key = id;   // CPUs with no cache at this level form their own group
            for (var k : m.caches()) {
                if (k.level() == level && k.sharedCpus().contains(id)) {
                    key = k.sharedCpus().stream().min(Integer::compare).orElse(id);
                    break;
                }
            }
            groups.computeIfAbsent(key, x -> new ArrayList<>()).add(id);
        }
        return groups;
    }

    /** Fill one L2 group completely before moving to the next. */
    public static List<Integer> closeByL2(MachineInfo m) {
        List<Integer> out = new ArrayList<>();
        groupsByCache(m, 2).values().forEach(out::addAll);
        return out;
    }

    /** One CPU from each L2 group in turn, so threads spread across clusters. */
    public static List<Integer> spreadByL2(MachineInfo m) {
        List<List<Integer>> g = new ArrayList<>(groupsByCache(m, 2).values());
        List<Integer> out = new ArrayList<>();
        for (int i = 0; out.size() < m.cpus().size(); i++)
            for (List<Integer> grp : g)
                if (i < grp.size()) out.add(grp.get(i));
        return out;
    }
}