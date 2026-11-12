
package com.dipx.client.gui;

import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleCategory;
import com.dipx.client.module.ModuleRegistry;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

public final class DIPXScreen extends Screen {
    private ModuleCategory selected = ModuleCategory.CRYSTAL_PVP;
    private int page;

    private static final int PANEL_W = 430;
    private static final int PANEL_H = 270;

    public DIPXScreen() {
        super(Text.literal("DIPX CLIENT"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;

        ctx.fill(x, y, x + PANEL_W, y + PANEL_H, 0xF0141420);
        ctx.fill(x, y, x + PANEL_W, y + 2, 0xFFB46CFF);

        ctx.drawText(textRenderer, "DIPX CLIENT",
                x + 14, y + 12, 0xFFE4CEFF, false);

        ctx.drawText(textRenderer,
                "ALL-ROUNDER MONSTER  •  RIGHT SHIFT",
                x + 14, y + 27, 0xFF9B91AA, false);

        ctx.fill(x + 10, y + 45,
                x + 112, y + PANEL_H - 12, 0xFF201D2B);

        ModuleCategory[] cats = ModuleCategory.values();

        for (int i = 0; i < cats.length; i++) {
            int cy = y + 52 + i * 18;
            boolean active = cats[i] == selected;

            if (active) {
                ctx.fill(x + 14, cy - 3, x + 108, cy + 12,
                        0xFF59377B);
            }

            ctx.drawText(textRenderer, cats[i].title,
                    x + 19, cy,
                    active ? 0xFFFFFFFF : 0xFFB8B2C4, false);
        }

        List<DIPXModule> visible = ModuleRegistry.all()
                .stream()
                .filter(m -> m.category() == selected)
                .toList();

        int perPage = 9;
        int start = page * perPage;

        for (int i = start; i < Math.min(start + perPage, visible.size()); i++) {
            DIPXModule m = visible.get(i);

            int local = i - start;
            int col = local % 2;
            int row = local / 2;

            int bx = x + 124 + col * 145;
            int by = y + 54 + row * 43;

            ctx.fill(bx, by, bx + 135, by + 34,
                    m.enabled() ? 0xFF493064 : 0xFF292633);

            ctx.drawText(textRenderer, m.name(),
                    bx + 7, by + 7, 0xFFF2ECFA, false);

            ctx.drawText(textRenderer,
                    m.enabled() ? "ON" : "OFF",
                    bx + 7, by + 20,
                    m.enabled() ? 0xFFB98BFF : 0xFF96909F, false);
        }

        ctx.drawText(textRenderer,
                "Click a module to toggle  •  ESC to close",
                x + 124, y + PANEL_H - 22, 0xFF9992A6, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) {
            return super.mouseClicked(click, doubled);
        }

        double mouseX = click.x();
        double mouseY = click.y();

        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;

        if (mouseX >= x + 14 && mouseX <= x + 108
                && mouseY >= y + 49
                && mouseY <= y + 49 + ModuleCategory.values().length * 18) {

            int index = (int) ((mouseY - (y + 49)) / 18);

            if (index >= 0 && index < ModuleCategory.values().length) {
                selected = ModuleCategory.values()[index];
                page = 0;
                return true;
            }
        }

        List<DIPXModule> visible = ModuleRegistry.all()
                .stream()
                .filter(m -> m.category() == selected)
                .toList();

        int perPage = 9;

        for (int i = page * perPage;
             i < Math.min((page + 1) * perPage, visible.size()); i++) {

            int local = i - page * perPage;
            int col = local % 2;
            int row = local / 2;

            int bx = x + 124 + col * 145;
            int by = y + 54 + row * 43;

            if (mouseX >= bx && mouseX <= bx + 135
                    && mouseY >= by && mouseY <= by + 34) {
                visible.get(i).toggle();
                return true;
            }
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
