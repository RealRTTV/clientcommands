package net.earthcomputer.clientcommands.util;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;

public record BuildInfo(String commitHash) {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BuildInfo UNKNOWN = new BuildInfo("refs/heads/fabric");

    public static final BuildInfo INSTANCE = Util.make(() -> {
        Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

        Path buildInfoPath = FabricLoader.getInstance().getModContainer("clientcommands")
            .orElseThrow()
            .findPath("build_info.json")
            .orElse(null);
        if (buildInfoPath == null) {
            LOGGER.warn("Couldn't find build_info.json");
            return UNKNOWN;
        }

        try (BufferedReader reader = Files.newBufferedReader(buildInfoPath)) {
            BuildInfo result = gson.fromJson(reader, BuildInfo.class);
            if (result == null) {
                LOGGER.warn("build_info.json was null or empty");
                return UNKNOWN;
            }
            return result;
        } catch (Throwable e) {
            LOGGER.warn("Couldn't read build_info.json", e);
            return UNKNOWN;
        }
    });

    public String shortCommitHash(int length) {
        return this == UNKNOWN || length >= commitHash.length() ? commitHash : commitHash.substring(0, length);
    }
}
