/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

public abstract class MonocleVertexFormats {
    // Step rate = 0 means per-vertex (not instanced)
    private static final int STEP_RATE = 0;

    // POS2: 2D position (x, y)
    public static final VertexFormat POS2 = VertexFormat.builder(STEP_RATE)
        .addAttribute("Position", GpuFormat.RG32_FLOAT) // 2 floats (x, y)
        .build();

    // POS2_COLOR: 2D position + RGBA color (4 bytes, normalized)
    public static final VertexFormat POS2_COLOR = VertexFormat.builder(STEP_RATE)
        .addAttribute("Position", GpuFormat.RG32_FLOAT) // 2 floats (x, y)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)   // 4 bytes (RGBA, 0-255)
        .build();

    // POS2_TEXTURE_COLOR: 2D position + UV texture coords + RGBA color
    public static final VertexFormat POS2_TEXTURE_COLOR = VertexFormat.builder(STEP_RATE)
        .addAttribute("Position", GpuFormat.RG32_FLOAT) // 2 floats (x, y)
        .addAttribute("Texture", GpuFormat.RG32_FLOAT) // 2 floats (u, v)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)   // 4 bytes (RGBA, 0-255)
        .build();

    private MonocleVertexFormats() {}
}
