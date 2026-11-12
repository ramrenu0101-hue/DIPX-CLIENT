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
    private int modulePage;
    private int categoryPage;

    private static final int DESIGN_W = 430;
    private static final int DESIGN_H = 270;
    private static final int NAV_W = 104;

    public DIPXScreen() {
        super(Text.literal("DIPX CLIENT"));
    }

    private int panelW() { return Math.max(180, Math.min(DESIGN_W, width - 8)); }
    private int panelH() { return Math.max(120, Math.min(DESIGN_H, height - 8)); }
    private int left() { return (width - panelW()) / 2; }
    private int top() { return (height - panelH()) / 2; }
    private int navW() { return Math.min(NAV_W, Math.max(72, panelW() / 3)); }
    private int perPage() { return panelH() < 205 ? 4 : 6; }
    private int categorySlots() { return Math.max(1, (panelH() - 76) / 16); }

    private List<DIPXModule> visibleModules() {
        return ModuleRegistry.all().stream().filter(m -> m.category() == selected).toList();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        int x = left(), y = top(), w = panelW(), h = panelH(), nav = navW();

        ctx.fill(x, y, x + w, y + h, 0xF0141420);
        ctx.fill(x, y, x + w, y + 2, 0xFFB46CFF);
        ctx.drawText(textRenderer, "DIPX CLIENT", x + 10, y + 8, 0xFFE4CEFF, false);
        ctx.drawText(textRenderer, "ALL-ROUNDER MONSTER", x + 10, y + 22, 0xFF9B91AA, false);
        ctx.fill(x + 8, y + 42, x + nav, y + h - 26, 0xFF201D2B);

        ModuleCategory[] cats = ModuleCategory.values();
        int slots = categorySlots();
        int firstCat = categoryPage * slots;
        for (int i = firstCat; i < Math.min(firstCat + slots, cats.length); i++) {
            int cy = y + 48 + (i - firstCat) * 16;
            boolean active = cats[i] == selected;
            if (active) ctx.fill(x + 11, cy - 2, x + nav - 3, cy + 12, 0xFF59377B);
            String label = textRenderer.trimToWidth(cats[i].title, nav - 18);
            ctx.drawText(textRenderer, label, x + 15, cy, active ? 0xFFFFFFFF : 0xFFB8B2C4, false);
        }

        if (cats.length > slots) {
            String catPageLabel = (categoryPage + 1) + "/" + (((cats.length - 1) / slots) + 1);
            ctx.drawText(textRenderer, "CAT " + catPageLabel, x + 13, y + h - 20, 0xFFB98BFF, false);
        }

        int contentX = x + nav + 12;
        int contentW = w - nav - 20;
        int columns = 2;
        int gap = 6;
        int cardW = Math.max(55, (contentW - gap) / columns);
        int cardH = panelH() < 205 ? 31 : 35;
        int rowGap = 7;
        List<DIPXModule> modules = visibleModules();
        int per = perPage();
        int start = modulePage * per;

        for (int i = start; i < Math.min(start + per, modules.size()); i++) {
            int local = i - start;
            int bx = contentX + (local % columns) * (cardW + gap);
            int by = y + 48 + (local / columns) * (cardH + rowGap);
            DIPXModule module = modules.get(i);
            ctx.fill(bx, by, bx + cardW, by + cardH, module.enabled() ? 0xFF493064 : 0xFF292633);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(module.name(), cardW - 10),
                    bx + 5, by + 5, 0xFFF2ECFA, false);
            ctx.drawText(textRenderer, module.enabled() ? "ON" : "OFF",
                    bx + 5, by + (cardH < 34 ? 17 : 20),
                    module.enabled() ? 0xFFB98BFF : 0xFF96909F, false);
        }

        int pages = Math.max(1, (modules.size() + per - 1) / per);
        String footer = "MODULES " + (modulePage + 1) + "/" + pages + "   •   ESC CLOSE";
        ctx.drawText(textRenderer, textRenderer.trimToWidth(footer, contentW),
                contentX, y + h - 17, 0xFF9992A6, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        double mx = click.x(), my = click.y();
        int x = left(), y = top(), h = panelH(), nav = navW();
        ModuleCategory[] cats = ModuleCategory.values();
        int slots = categorySlots();

        if (mx >= x + 11 && mx <= x + nav - 3 && my >= y + 46 && my < y + h - 25) {
            int index = categoryPage * slots + (int) ((my - (y + 46)) / 16);
            if (index >= 0 && index < cats.length && index < (categoryPage + 1) * slots) {
                selected = cats[index];
                modulePage = 0;
                return true;
            }
        }

        if (mx >= x + 8 && mx <= x + nav && my >= y + h - 25 && my <= y + h - 5
                && cats.length > slots) {
            int maxPage = (cats.length - 1) / slots;
            categoryPage = (categoryPage + 1) % (maxPage + 1);
            return true;
        }

        int contentX = x + nav + 12;
        int contentW = panelW() - nav - 20;
        int cardW = Math.max(55, (contentW - 6) / 2);
        int cardH = panelH() < 205 ? 31 : 35;
        int rowGap = 7;
        List<DIPXModule> modules = visibleModules();
        int per = perPage();

        for (int i = modulePage * per; i < Math.min((modulePage + 1) * per, modules.size()); i++) {
            int local = i - modulePage * per;
            int bx = contentX + (local % 2) * (cardW + 6);
            int by = y + 48 + (local / 2) * (cardH + rowGap);
            if (mx >= bx && mx <= bx + cardW && my >= by && my <= by + cardH) {
                modules.get(i).toggle();
                return true;
            }
        }

        if (mx >= contentX && mx <= contentX + contentW && my >= y + h - 22 && my <= y + h) {
            int pages = Math.max(1, (modules.size() + per - 1) / per);
            modulePage = (modulePage + 1) % pages;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
