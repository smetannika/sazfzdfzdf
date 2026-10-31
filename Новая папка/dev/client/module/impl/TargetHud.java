package dev.client.module.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.client.gui.ClickGui;
import dev.client.gui.Draw;
import dev.client.module.BoolSetting;
import dev.client.module.HudModule;
import dev.client.module.NumSetting;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Target HUD (new look): dark rounded card with a theme-coloured glow,
 * head on the left, name + "HP: x" and a smooth gradient health bar.
 * Target = entity under the crosshair, kept for "Linger (s)" seconds.
 * While the ClickGUI is open a preview card is shown so it can be dragged.
 */
public class TargetHud extends HudModule {

    private final BoolSetting showHead;
    private final BoolSetting showArmor;
    private final BoolSetting showDist;
    private final BoolSetting playersOnly;
    private final NumSetting linger;

    private LivingEntity target;     // current target (null after linger runs out)
    private LivingEntity shown;      // last target, kept while the card fades out
    private Object lastRef;
    private long seen;
    private long lastNs = System.nanoTime();
    private float fade;
    private float shownHp;
    private float trailHp;

    public TargetHud() {
        super("Target HUD", 300, 230, true);
        showHead = add(new BoolSetting("Show head", true));
        showArmor = add(new BoolSetting("Armor", true));
        showDist = add(new BoolSetting("Distance", true));
        playersOnly = add(new BoolSetting("Players only", false));
        linger = add(new NumSetting("Linger (s)", 0.0, 10.0, 0.5, 3.0));
    }

    private static float approach(float v, float t, float step) {
        float d = t - v;
        if (d > step) return v + step;
        if (d < -step) return v - step;
        return t;
    }

    @Override
    public void render(MatrixStack ms) {
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();
        float dt = Math.min(0.05f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;

        boolean preview = mc.currentScreen instanceof ClickGui;

        // ── pick target ──
        LivingEntity live = null;
        HitResult hr = mc.crosshairTarget;
        if (hr instanceof EntityHitResult) {
            Entity en = ((EntityHitResult) hr).getEntity();
            if (en instanceof LivingEntity && en != mc.player && en.isAlive()
                    && (!playersOnly.value || en instanceof PlayerEntity)) {
                live = (LivingEntity) en;
            }
        }
        if (live != null) {
            target = live;
            seen = nowMs;
        }
        if (target != null && (!target.isAlive() || nowMs - seen > (long) (linger.value * 1000.0))) {
            target = null;
        }
        if (target != null) shown = target;

        boolean active = target != null || preview;
        fade = approach(fade, active ? 1f : 0f, dt * 8f);
        if (fade < 0.02f) return;
        float a = fade * fade * (3f - 2f * fade);

        LivingEntity e = target != null ? target : (preview ? null : shown);

        // ── data ──
        String name = e != null ? e.getName().getString() : "Target";
        float hp = e != null ? e.getHealth() + e.getAbsorptionAmount() : 17f;
        float maxHp = e != null ? Math.max(1f, e.getMaxHealth()) : 20f;
        float ratio = Math.max(0f, Math.min(1f, hp / maxHp));

        if (lastRef != e) {
            lastRef = e;
            shownHp = ratio;
            trailHp = ratio;
        }
        shownHp += (ratio - shownHp) * Math.min(1f, dt * 10f);
        if (trailHp < shownHp) trailHp = shownHp;
        else trailHp = Math.max(shownHp, trailHp - dt * 0.35f);

        List<ItemStack> gear = new ArrayList<>();
        if (e != null && showArmor.value) {
            for (ItemStack st : e.getArmorItems()) gear.add(st);
            Collections.reverse(gear);
            gear.add(e.getMainHandStack());
            List<ItemStack> nonEmpty = new ArrayList<>();
            for (ItemStack st : gear) if (!st.isEmpty()) nonEmpty.add(st);
            gear = nonEmpty;
        }
        boolean armorRow = !gear.isEmpty();

        // ── geometry ──
        float nameScale = 1.1f;
        float nameW = mc.textRenderer.getWidth(name) * nameScale;
        String distStr = "";
        float distW = 0f;
        if (showDist.value && e != null && mc.player != null) {
            distStr = String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(e));
            distW = mc.textRenderer.getWidth(distStr);
        }
        int textX = showHead.value ? 52 : 10;
        float need = textX + Math.max(nameW + (distW > 0 ? distW + 10f : 0f), 70f) + 10f;
        this.w = (int) Math.max(150, Math.ceil(need));
        this.h = 52 + (armorRow ? 22 : 0);

        float X = this.x;
        float Y = this.y + (1f - a) * 6f;

        // ── card ──
        Draw.glow(ms, X, Y, w, h, 9, Draw.a1(), 10, 0.40f * a);
        Draw.rrect(ms, X, Y, w, h, 9, Draw.ca(0xF0101014, a));

        // head
        if (showHead.value) {
            float hx = X + 8, hy = Y + 8;
            Draw.rrect(ms, hx, hy, 36, 36, 7, Draw.ca(0xFF26262C, a));
            if (e instanceof AbstractClientPlayerEntity) {
                mc.getTextureManager().bindTexture(((AbstractClientPlayerEntity) e).getSkinTexture());
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.color4f(1f, 1f, 1f, a);
                DrawableHelper.drawTexture(ms, (int) hx + 3, (int) hy + 3, 30, 30, 8f, 8f, 8, 8, 64, 64);
                DrawableHelper.drawTexture(ms, (int) hx + 3, (int) hy + 3, 30, 30, 40f, 8f, 8, 8, 64, 64);
                RenderSystem.color4f(1f, 1f, 1f, 1f);
            } else {
                String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                ms.push();
                ms.translate(hx + 18 - mc.textRenderer.getWidth(l) * 1.8f / 2f, hy + 18 - 8f * 1.8f / 2f, 0);
                ms.scale(1.8f, 1.8f, 1f);
                mc.textRenderer.draw(ms, l, 0, 0, Draw.ca(0xFFFFFFFF, a));
                ms.pop();
            }
        }

        // name + hp
        if (a > 0.03f) {
            ms.push();
            ms.translate(X + textX, Y + 9, 0);
            ms.scale(nameScale, nameScale, 1f);
            mc.textRenderer.drawWithShadow(ms, name, 0, 0, Draw.ca(0xFFFFFFFF, a));
            ms.pop();
            mc.textRenderer.draw(ms, String.format(Locale.ROOT, "HP: %.1f", hp), X + textX, Y + 22,
                    Draw.ca(0xFFC8C8D2, a));
            if (!distStr.isEmpty()) {
                mc.textRenderer.draw(ms, distStr, X + w - 8 - distW, Y + 10, Draw.ca(0xFF8A8A94, a));
            }
        }

        // health bar
        float bx = X + textX, by = Y + 35, bw = w - textX - 10, bh = 8;
        Draw.rrect(ms, bx, by, bw, bh, 4, Draw.ca(0xFF2A2A31, a));
        if (trailHp > shownHp + 0.002f) {
            Draw.rrect(ms, bx, by, Math.max(bh, bw * trailHp), bh, 4, Draw.ca(0xFFFFFFFF, 0.32f * a));
        }
        if (shownHp > 0.005f) {
            Draw.rrectG(ms, bx, by, Math.max(bh, bw * shownHp), bh, 4,
                    Draw.ca(Draw.a1(), a), Draw.ca(Draw.a2(), a));
        }

        // armor / held item row
        if (armorRow) {
            float ix = X + 8, iy = Y + 54;
            for (ItemStack st : gear) {
                mc.getItemRenderer().renderInGuiWithOverrides(st, (int) ix, (int) iy);
                mc.getItemRenderer().renderGuiItemOverlay(mc.textRenderer, st, (int) ix, (int) iy);
                ix += 18;
            }
        }
    }
}
