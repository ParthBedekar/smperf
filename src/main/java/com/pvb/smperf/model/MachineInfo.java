package com.pvb.smperf.model;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.util.List;

public record MachineInfo(
        String model,
        long ramBytes,
        int logicalCount,
        int physicalCount,
        String pinning,
        List<Cpu> cpus,
        List<Cache> caches) {

    /** "pinning" was added for the macOS port. Older engines / saved sessions don't
     *  have it; those all came from the Linux engine, which always pins. */
    public boolean pinningSupported() {
        return pinning == null || "supported".equals(pinning);
    }

    public record Cpu(
            int id,
            @SerializedName("package") int pkg,   // "package" is a Java keyword
            int coreId,
            int physicalCore,
            String type,
            long maxKhz,
            List<Integer> siblings) {}

    public record Cache(
            int level,
            String type,
            long sizeBytes,
            List<Integer> sharedCpus) {}

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public static MachineInfo fromJson(String json) {
        return GSON.fromJson(json, MachineInfo.class);
    }
}