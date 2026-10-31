package dev.client.gui;

import dev.client.Config;
import dev.client.module.BindSetting;
import dev.client.module.BoolSetting;
import dev.client.module.HudModule;
import dev.client.module.Module;
import dev.client.module.ModuleManager;
import dev.client.module.NumSetting;
import dev.client.module.Setting;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * New ClickGUI: one dark rounded panel per category (+ a Theme panel), side by side.
 *
 *  LMB on a module  – toggle          RMB on a module (or the gear) – settings
 *  MMB on a module  – bind a key      LMB on a HUD block (outside panels) – drag it
 *  Esc              – close           Esc / Backspace / Delete while binding – clear bind
 */
public class ClickGui extends Screen {

    // layout
    private static final int PW = 118;      // panel width
    private static final int GAP = 8;       // gap between panels
    private static final int HEAD = 24;     // panel header height
    private static final int ROW = 19;      // module row pitch
    private static final int TOP = 30;      // panels top offset
    private static final int THEME_ROW = 22;

    // colours
    private static final int BG = 0xF01B1B20;
    private static final int ROW_OFF = 0xFF25252B;
    private static final int ROW_ON = 0xFF3C3C44;
    private static final int ROW_HOVER = 0xFF4A4A53;
    private static final int SET_BG = 0xFF202026;
    private static final int TXT = 0xFFFFFFFF;
    private static final int TXT_OFF = 0xFFB0B0B9;
    private static final int DIM = 0xFF8A8A94;
    private static final int TRACK = 0xFF34343C;

    // hit types
    private static final int H_MODULE = 0, H_MODBIND = 1, H_BOOL = 2, H_NUM = 3, H_BIND = 4, H_THEME = 5;

    private static final class Hit {
        int type;
        float x, y, w, h;
        float sx, sw;           // slider track (NUM)
        Module m;
        Setting s;
        int idx;
    }

    private final List<Hit> hits = new ArrayList<>();
    private Module binding;
    private BindSetting bindingSetting;
    private Hit dragging;
    private HudModule dragHud;
    private double dragOffX, dragOffY;
    private float scroll, scrollTarget, maxScroll;
    private float curScale = 1f;
    private final long openedAt;
    private long lastNs;

    public ClickGui() {
        super(new LiteralText("ClickGUI"));
        openedAt = System.nanoTime();
        lastNs = openedAt;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ───────────────────────── helpers ─────────────────────────

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float approach(float v, float target, float step) {
        float d = target - v;
        if (d > step) return v + step;
        if (d < -step) return v - step;
        return target;
    }

    private float uiScale() {
        int n = Module.Category.values().length + 1;
        float cw = n * PW + (n - 1) * GAP + 24;
        return Math.max(0.5f, Math.min(1f, width / cw));
    }

    private static List<Module> modulesOf(Module.Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.modules) if (m.category == c) out.add(m);
        return out;
    }

    private static String keyName(int k) {
        try {
            String n = InputUtil.fromKeyCode(k, -1).getLocalizedText().getString();
            n = n.toUpperCase(Locale.ROOT);
            return n.length() > 7 ? n.substring(0, 7) : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static float settingH(Setting s) {
        if (s instanceof NumSetting) return 25f;
        return 16f;
    }

    private static float settingsH(Module m) {
        float h = 16f + 5f;                      // "Bind" row + padding
        for (Setting s : m.settings) h += settingH(s);
        return h;
    }

    private void text(MatrixStack ms, String t, float x, float y, int c) {
        if (((c >>> 24) & 0xFF) < 6) return;     // vanilla turns alpha<4 into opaque
        textRenderer.draw(ms, t, x, y, c);
    }

    private void textR(MatrixStack ms, String t, float right, float y, int c) {
        text(ms, t, right - textRenderer.getWidth(t), y, c);
    }

    private void textC(MatrixStack ms, String t, float cx, float y, int c) {
        text(ms, t, cx - textRenderer.getWidth(t) / 2f, y, c);
    }

    private void textSmallR(MatrixStack ms, String t, float right, float y, float sc, int c) {
        if (((c >>> 24) & 0xFF) < 6) return;
        ms.push();
        ms.translate(right - textRenderer.getWidth(t) * sc, y, 0);
        ms.scale(sc, sc, 1f);
        textRenderer.draw(ms, t, 0, 0, c);
        ms.pop();
    }

    private Hit hit(int type, float x, float y, float w, float h) {
        Hit hh = new Hit();
        hh.type = type;
        hh.x = x; hh.y = y; hh.w = w; hh.h = h;
        hits.add(hh);
        return hh;
    }

    private static boolean inside(Hit h, float x, float y) {
        return x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h;
    }

    // ───────────────────────── render ─────────────────────────

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        float open = ease((now - openedAt) / 220_000_000f);
        float s = uiScale();
        curScale = s;

        scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1f, dt * 14f);

        DrawableHelper.fill(ms, 0, 0, width, height, ((int) (120 * open)) << 24);
        hits.clear();

        drawHudOutlines(ms, open);

        float lmx = mouseX / s, lmy = mouseY / s;
        float lw = width / s;
        Module.Category[] cats = Module.Category.values();
        int n = cats.length + 1;
        float cw = n * PW + (n - 1) * GAP;
        float ox = (lw - cw) / 2f;
        float oy = TOP - scroll + (1f - open) * 14f;

        float tallest = 0f;
        ms.push();
        ms.scale(s, s, 1f);
        float px = ox;
        for (Module.Category c : cats) {
            tallest = Math.max(tallest, drawPanel(ms, c, px, oy, lmx, lmy, open, dt));
            px += PW + GAP;
        }
        tallest = Math.max(tallest, drawThemePanel(ms, px, oy, lmx, lmy, open));
        ms.pop();

        maxScroll = Math.max(0f, TOP + tallest + 34f - height / s);

        String hint = "LMB toggle  \u00b7  RMB settings  \u00b7  MMB bind  \u00b7  drag HUD blocks with LMB";
        textC(ms, hint, width / 2f, height - 13, Draw.ca(DIM, open));
    }

    private void drawHudOutlines(MatrixStack ms, float open) {
        for (Module m : ModuleManager.modules) {
            if (!(m instanceof HudModule) || !m.enabled) continue;
            HudModule h = (HudModule) m;
            int c = Draw.ca(Draw.a1(), (h == dragHud ? 0.9f : 0.45f) * open);
            int x1 = h.x - 2, y1 = h.y - 2, x2 = h.x + h.w + 2, y2 = h.y + h.h + 2;
            DrawableHelper.fill(ms, x1, y1, x2, y1 + 1, c);
            DrawableHelper.fill(ms, x1, y2 - 1, x2, y2, c);
            DrawableHelper.fill(ms, x1, y1, x1 + 1, y2, c);
            DrawableHelper.fill(ms, x2 - 1, y1, x2, y2, c);
        }
    }

    private float drawPanel(MatrixStack ms, Module.Category cat, float x, float y,
                            float mx, float my, float open, float dt) {
        List<Module> mods = modulesOf(cat);

        float h = HEAD + 2;
        for (Module m : mods) {
            m.openAnim = approach(m.openAnim, m.open ? 1f : 0f, dt * 9f);
            h += ROW + settingsH(m) * ease(m.openAnim);
        }
        h += 4;

        Draw.glow(ms, x, y, PW, h, 8, 0xFF000000, 8, 0.30f * open);
        Draw.rrect(ms, x, y, PW, h, 8, Draw.ca(BG, open));

        textC(ms, cat.label, x + PW / 2f, y + 8f, Draw.ca(TXT, open));
        Draw.rrectG(ms, x + 10, y + HEAD - 3, PW - 20, 1.5f, 0.7f,
                Draw.ca(Draw.a1(), 0.85f * open), Draw.ca(Draw.a2(), 0.85f * open));

        float ry = y + HEAD + 2;
        for (Module m : mods) {
            float rx = x + 4, rw = PW - 8, rh = ROW - 2;
            boolean hov = mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;

            m.toggleAnim = approach(m.toggleAnim, m.enabled ? 1f : 0f, dt * 10f);
            float ta = ease(m.toggleAnim);
            int bg = Draw.lerp(ROW_OFF, ROW_ON, ta);
            if (hov) bg = Draw.lerp(bg, ROW_HOVER, 0.35f);
            Draw.rrect(ms, rx, ry, rw, rh, 5, Draw.ca(bg, open));

            text(ms, m.name, rx + 7, ry + (rh - 8) / 2f + 0.5f, Draw.ca(Draw.lerp(TXT_OFF, TXT, ta), open));

            float right = rx + rw - 6;
            if (!m.settings.isEmpty()) {
                drawGear(ms, right - 4f, ry + rh / 2f, Draw.ca(m.open ? Draw.a1() : 0xFF7A7A84, open), Draw.ca(bg, open));
                right -= 14f;
            }
            if (binding == m) {
                textSmallR(ms, "...", right, ry + (rh - 7) / 2f + 0.5f, 0.85f, Draw.ca(Draw.a1(), open));
            } else if (m.key > 0) {
                textSmallR(ms, keyName(m.key), right, ry + (rh - 7) / 2f + 0.5f, 0.85f, Draw.ca(DIM, open));
            }

            hit(H_MODULE, rx, ry, rw, rh).m = m;
            ry += ROW;

            float sh = settingsH(m) * ease(m.openAnim);
            if (sh > 0.5f) {
                drawSettings(ms, m, x, ry, sh, mx, my, open, dt);
                ry += sh;
            }
        }
        return h;
    }

    private void drawGear(MatrixStack ms, float cx, float cy, int c, int hole) {
        Draw.rrect(ms, cx - 3.5f, cy - 3.5f, 7, 7, 3.5f, c);
        Draw.rrect(ms, cx - 1.5f, cy - 1.5f, 3, 3, 1.5f, hole);
        Draw.rrect(ms, cx - 0.8f, cy - 5.2f, 1.6f, 2.4f, 0.5f, c);
        Draw.rrect(ms, cx - 0.8f, cy + 2.8f, 1.6f, 2.4f, 0.5f, c);
        Draw.rrect(ms, cx - 5.2f, cy - 0.8f, 2.4f, 1.6f, 0.5f, c);
        Draw.rrect(ms, cx + 2.8f, cy - 0.8f, 2.4f, 1.6f, 0.5f, c);
    }

    private void drawSettings(MatrixStack ms, Module m, float x, float top, float visH,
                              float mx, float my, float open, float dt) {
        float bx = x + 4, bw = PW - 8;
        float fullH = settingsH(m);
        boolean interactive = m.openAnim > 0.95f;

        Draw.scissor(x * curScale, top * curScale, PW * curScale, visH * curScale);
        Draw.rrect(ms, bx, top - 3, bw, fullH + 1, 5, Draw.ca(SET_BG, open));

        float sy = top + 1;

        // module bind row
        text(ms, "Bind", bx + 7, sy + 4, Draw.ca(TXT_OFF, open));
        String bv = binding == m ? "press a key..." : (m.key > 0 ? "[" + keyName(m.key) + "]" : "none");
        textR(ms, bv, bx + bw - 7, sy + 4, Draw.ca(binding == m ? Draw.a1() : DIM, open));
        if (interactive) hit(H_MODBIND, bx, sy, bw, 16).m = m;
        sy += 16;

        for (Setting st : m.settings) {
            if (st instanceof BoolSetting) {
                BoolSetting b = (BoolSetting) st;
                st.anim = approach(st.anim, b.value ? 1f : 0f, dt * 12f);
                float a = ease(st.anim);
                text(ms, st.name, bx + 7, sy + 4, Draw.ca(Draw.lerp(TXT_OFF, TXT, a), open));
                float cbx = bx + bw - 17, cby = sy + 3.5f;
                Draw.rrect(ms, cbx, cby, 9, 9, 2.5f, Draw.ca(TRACK, open));
                if (a > 0.02f) {
                    float inset = (1f - a) * 4f;
                    Draw.rrectG(ms, cbx + inset, cby + inset, 9 - inset * 2, 9 - inset * 2, 2.5f,
                            Draw.ca(Draw.a1(), open), Draw.ca(Draw.a2(), open));
                }
                if (interactive) hit(H_BOOL, bx, sy, bw, 16).s = st;
                sy += 16;
            } else if (st instanceof NumSetting) {
                NumSetting n = (NumSetting) st;
                text(ms, st.name, bx + 7, sy + 3, Draw.ca(TXT_OFF, open));
                textR(ms, fmt(n.value), bx + bw - 7, sy + 3, Draw.ca(TXT, open));
                float tx = bx + 7, tw = bw - 14, ty = sy + 17;
                double range = n.max - n.min;
                float f = range <= 0 ? 0f : (float) Math.max(0.0, Math.min(1.0, (n.value - n.min) / range));
                Draw.rrect(ms, tx, ty, tw, 3, 1.5f, Draw.ca(TRACK, open));
                Draw.rrectG(ms, tx, ty, Math.max(3f, tw * f), 3, 1.5f,
                        Draw.ca(Draw.a1(), open), Draw.ca(Draw.a2(), open));
                Draw.rrect(ms, tx + tw * f - 3f, ty - 1.5f, 6, 6, 3, Draw.ca(0xFFFFFFFF, open));
                if (interactive) {
                    Hit hh = hit(H_NUM, tx - 3, sy + 10, tw + 6, 14);
                    hh.s = st;
                    hh.sx = tx;
                    hh.sw = tw;
                }
                sy += 25;
            } else if (st instanceof BindSetting) {
                BindSetting b = (BindSetting) st;
                text(ms, st.name, bx + 7, sy + 4, Draw.ca(TXT_OFF, open));
                String v = bindingSetting == b ? "press a key..." : (b.key > 0 ? "[" + keyName(b.key) + "]" : "none");
                textR(ms, v, bx + bw - 7, sy + 4, Draw.ca(bindingSetting == b ? Draw.a1() : DIM, open));
                if (interactive) hit(H_BIND, bx, sy, bw, 16).s = st;
                sy += 16;
            } else {
                text(ms, st.name, bx + 7, sy + 4, Draw.ca(TXT_OFF, open));
                sy += 16;
            }
        }
        Draw.endScissor();
    }

    private float drawThemePanel(MatrixStack ms, float x, float y, float mx, float my, float open) {
        int n = Draw.THEME_NAMES.length;
        float h = HEAD + 4 + n * THEME_ROW + 3;

        Draw.glow(ms, x, y, PW, h, 8, 0xFF000000, 8, 0.30f * open);
        Draw.rrect(ms, x, y, PW, h, 8, Draw.ca(BG, open));
        textC(ms, "Theme", x + PW / 2f, y + 8f, Draw.ca(TXT, open));
        Draw.rrectG(ms, x + 10, y + HEAD - 3, PW - 20, 1.5f, 0.7f,
                Draw.ca(Draw.a1(), 0.85f * open), Draw.ca(Draw.a2(), 0.85f * open));

        float ry = y + HEAD + 2;
        for (int i = 0; i < n; i++) {
            float rx = x + 4, rw = PW - 8, rh = THEME_ROW - 3;
            boolean hov = mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
            boolean sel = Draw.theme == i;
            int bg = sel ? ROW_ON : ROW_OFF;
            if (hov) bg = Draw.lerp(bg, ROW_HOVER, 0.3f);
            Draw.rrect(ms, rx, ry, rw, rh, 5, Draw.ca(bg, open));
            text(ms, Draw.THEME_NAMES[i], rx + 7, ry + (rh - 8) / 2f + 0.5f,
                    Draw.ca(sel ? TXT : TXT_OFF, open));
            float pw = 34, ph = 8, pxx = rx + rw - pw - 7, pyy = ry + (rh - ph) / 2f;
            if (sel) Draw.glow(ms, pxx, pyy, pw, ph, 4, Draw.a1(i), 5, 0.5f * open);
            Draw.rrectG(ms, pxx, pyy, pw, ph, 4, Draw.ca(Draw.a1(i), open), Draw.ca(Draw.a2(i), open));
            hit(H_THEME, rx, ry, rw, rh).idx = i;
            ry += THEME_ROW;
        }
        return h;
    }

    // ───────────────────────── input ─────────────────────────

    private void slide(Hit h, double lx) {
        NumSetting n = (NumSetting) h.s;
        double f = Math.max(0.0, Math.min(1.0, (lx - h.sx) / h.sw));
        double v = n.min + f * (n.max - n.min);
        if (n.step > 0) v = n.min + Math.round((v - n.min) / n.step) * n.step;
        v = Math.max(n.min, Math.min(n.max, v));
        n.set(v);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (binding != null || bindingSetting != null) return true;   // waiting for a key

        float s = uiScale();
        float lx = (float) (mx / s), ly = (float) (my / s);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!inside(h, lx, ly)) continue;
            switch (h.type) {
                case H_MODULE:
                    if (button == 0) {
                        if (!h.m.settings.isEmpty() && lx > h.x + h.w - 18) h.m.open = !h.m.open;
                        else { h.m.toggle(); Config.save(); }
                    } else if (button == 1) {
                        h.m.open = !h.m.open;
                    } else if (button == 2) {
                        binding = h.m;
                    }
                    break;
                case H_MODBIND:
                    if (button == 0) binding = h.m;
                    break;
                case H_BOOL:
                    if (button == 0) {
                        BoolSetting b = (BoolSetting) h.s;
                        b.value = !b.value;
                        Config.save();
                    }
                    break;
                case H_NUM:
                    if (button == 0) {
                        dragging = h;
                        slide(h, lx);
                    }
                    break;
                case H_BIND:
                    if (button == 0) bindingSetting = (BindSetting) h.s;
                    break;
                case H_THEME:
                    if (button == 0) Draw.theme = h.idx;
                    break;
                default:
                    break;
            }
            return true;
        }

        if (button == 0) {
            for (Module m : ModuleManager.modules) {
                if (m instanceof HudModule && m.enabled && ((HudModule) m).contains(mx, my)) {
                    dragHud = (HudModule) m;
                    dragOffX = mx - dragHud.x;
                    dragOffY = my - dragHud.y;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            slide(dragging, mx / uiScale());
            return true;
        }
        if (dragHud != null) {
            dragHud.x = Math.max(0, Math.min(Math.max(0, width - dragHud.w), (int) (mx - dragOffX)));
            dragHud.y = Math.max(0, Math.min(Math.max(0, height - dragHud.h), (int) (my - dragOffY)));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null || dragHud != null) {
            dragging = null;
            dragHud = null;
            Config.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget - (float) amount * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (binding != null || bindingSetting != null) {
            boolean clear = keyCode == GLFW.GLFW_KEY_ESCAPE
                    || keyCode == GLFW.GLFW_KEY_BACKSPACE
                    || keyCode == GLFW.GLFW_KEY_DELETE;
            int k = clear ? -1 : keyCode;
            if (binding != null) binding.key = k;
            else bindingSetting.key = k;
            binding = null;
            bindingSetting = null;
            Config.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        binding = null;
        bindingSetting = null;
        Config.save();
        super.onClose();
    }
}
