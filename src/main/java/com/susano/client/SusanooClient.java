package com.susano.client;

import com.susano.SusanooEntity;
import com.susano.SusanooMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class SusanooClient implements ClientModInitializer {
    private static KeyBinding toggle, form, attack, special;
    private static SusanooEntity local;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(SusanooMod.SUSANOO, SusanooRenderer::new);

        toggle = key("key.susanoo.toggle", GLFW.GLFW_KEY_G);
        form = key("key.susanoo.form", GLFW.GLFW_KEY_H);
        attack = key("key.susanoo.attack", GLFW.GLFW_KEY_R);
        special = key("key.susanoo.special", GLFW.GLFW_KEY_V);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null) {
                local = null;
                return;
            }
            local = null;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof SusanooEntity s && mc.player.getUuid().equals(s.getOwnerUuid())) {
                    local = s;
                    break;
                }
            }
            if (mc.currentScreen != null) {
                return;
            }
            while (toggle.wasPressed()) send(SusanooMod.ACT_TOGGLE);
            while (form.wasPressed()) send(Screen.hasShiftDown() ? SusanooMod.ACT_FORM_DOWN : SusanooMod.ACT_FORM_UP);
            while (attack.wasPressed()) { /* vide la file ; le maintien est gere ci-dessous */ }
            if (attack.isPressed()) send(SusanooMod.ACT_ATTACK);
            while (special.wasPressed()) send(SusanooMod.ACT_SPECIAL);
        });

        HudRenderCallback.EVENT.register(SusanooClient::drawHud);
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "key.categories.susanoo"));
    }

    private static void send(int action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(action);
        ClientPlayNetworking.send(SusanooMod.ACTION, buf);
    }

    private static void drawHud(DrawContext ctx, float td) {
        SusanooEntity s = local;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null || s.isRemoved() || mc.options.hudHidden) {
            return;
        }
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int x = w / 2 - 91;
        int y = h - 70;
        float ratio = Math.max(0f, Math.min(1f, s.getShield() / s.getMaxShield()));
        int[] c = com.susano.SusanooColor.byId(s.getColorId()).glow;
        int argb = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        ctx.fill(x - 1, y - 1, x + 183, y + 5, 0xAA000000);
        ctx.fill(x, y, x + (int) (182 * ratio), y + 4, argb);
        Text label = Text.translatable("hud.susanoo.form." + s.getForm());
        ctx.drawTextWithShadow(mc.textRenderer, label, x, y - 11, argb);
    }
}
