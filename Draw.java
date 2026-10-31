package dev.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Self-contained 2D helpers for the new ClickGUI / Target HUD
 * (rounded rects, horizontal gradients, soft glow, scissor, theme colours).
 * Does not depend on the old Render2D, so nothing can break if Render2D changes.
 */
public final class Draw {
    private Draw() {}

    // ───────────── themes (the "Theme" panel in the ClickGUI) ─────────────
    public static final String[] THEME_NAMES = {
            "Анчоус", "Андрич", "Анорикс", "Абрикос", "Аквамарин", "Аметист", "Бежевый", "Бесподобный"
    };
    private static final int[][] THEME_COLORS = {
            {0xFFFF7AB6, 0xFF3D5AFE},
            {0xFF6FE3C1, 0xFFB57BFF},
            {0xFF6A3DFF, 0xFF3D8BFF},
            {0xFFFF6B6B, 0xFFFFA45B},
            {0xFF4DF0B0, 0xFF2E9BFF},
            {0xFFA566E0, 0xFF6A4BBF},
            {0xFFFFF1DC, 0xFFFFD0A0},
            {0xFFE02B3F, 0xFF7A1FD1}
    };
    public static int theme = 0;

    public static int a1() { return THEME_COLORS[theme][0]; }
    public static int a2() { return THEME_COLORS[theme][1]; }
    public static int a1(int i) { return THEME_COLORS[i][0]; }
    public static int a2(int i) { return THEME_COLORS[i][1]; }

    // ───────────── colour helpers ─────────────
    /** multiply alpha of an ARGB colour */
    public static int ca(int c, float a) {
        int al = (int) (((c >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (c & 0xFFFFFF);
    }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ───────────── rounded rectangles ─────────────
    private static final int SEG = 8;

    public static void rrect(MatrixStack ms, float x, float y, float w, float h, float r, int color) {
        rrectG(ms, x, y, w, h, r, color, color);
    }

    /** rounded rect with a left→right gradient */
    public static void rrectG(MatrixStack ms, float x, float y, float w, float h, float r, int cLeft, int cRight) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        Matrix4f m = ms.peek().getModel();

        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();

        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(GL11.GL_TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
        vtx(bb, m, x + w / 2f, y + h / 2f, lerp(cLeft, cRight, 0.5f));

        float[][] corner = {
                {x + w - r, y + r, -90},
                {x + w - r, y + h - r, 0},
                {x + r, y + h - r, 90},
                {x + r, y + r, 180}
        };
        float fx = 0, fy = 0;
        boolean first = true;
        for (float[] c : corner) {
            for (int i = 0; i <= SEG; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / SEG);
                float px = c[0] + (float) Math.cos(a) * r;
                float py = c[1] + (float) Math.sin(a) * r;
                vtx(bb, m, px, py, lerp(cLeft, cRight, (px - x) / w));
                if (first) { fx = px; fy = py; first = false; }
            }
        }
        vtx(bb, m, fx, fy, lerp(cLeft, cRight, (fx - x) / w));
        Tessellator.getInstance().draw();

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
    }

    private static void vtx(BufferBuilder bb, Matrix4f m, float x, float y, int c) {
        bb.vertex(m, x, y, 0f)
                .color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF)
                .next();
    }

    /** soft glow around a rounded rect (stack of faint expanding rects) */
    public static void glow(MatrixStack ms, float x, float y, float w, float h, float r, int color, int spread, float strength) {
        for (int i = spread; i >= 1; i--) {
            float t = (float) i / spread;
            float a = (1f - t) * (1f - t) * strength;
            rrect(ms, x - i, y - i, w + i * 2f, h + i * 2f, r + i, ca(color, a));
        }
    }

    // ───────────── scissor (coordinates in real GUI-scaled pixels) ─────────────
    public static void scissor(float x, float y, float w, float h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double s = mc.getWindow().getScaleFactor();
        int fbH = mc.getWindow().getFramebufferHeight();
        int sx = (int) Math.floor(x * s);
        int sy = (int) Math.floor(fbH - (y + h) * s);
        int sw = (int) Math.ceil(w * s);
        int sh = (int) Math.ceil(h * s);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.max(0, sx), Math.max(0, sy), Math.max(0, sw), Math.max(0, sh));
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
