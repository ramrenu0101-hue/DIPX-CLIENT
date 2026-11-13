package com.dipx.client.gui;

import com.dipx.client.module.DIPXModule;
import com.dipx.client.module.ModuleCategory;
import com.dipx.client.module.ModuleRegistry;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Original DIPX dark-purple PvP interface. The layout is deliberately drawn
 * with simple primitives (no shader, texture, or external GUI dependency) for
 * compatibility with mobile OpenGL drivers and touch input.
 */
public final class DIPXScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("DIPX/GUI");
    private static final int CATEGORY_ROW = 20;
    private static final int CARD_GAP = 6;
    private static boolean renderFailureLogged;

    private ModuleCategory selected = ModuleCategory.COMBAT;
    private int modulePage;
    private int categoryPage;

    public DIPXScreen() {
        super(Text.literal("DIPX CLIENT"));
    }

    private List<DIPXModule> visibleModules() {
        return ModuleRegistry.all().stream()
                .filter(module -> module.category() == selected)
                .toList();
    }

    private static int pageCount(int count, int perPage) {
        return Math.max(1, (count + Math.max(1, perPage) - 1) / Math.max(1, perPage));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        try {
            DIPXLayout layout = DIPXLayout.calculate(width, height);
            int x = layout.panelX();
            int y = layout.panelY();
            int w = layout.panelWidth();
            int h = layout.panelHeight();
            if (w <= 0 || h <= 0) return;

            List<DIPXModule> all = ModuleRegistry.all();
            List<DIPXModule> modules = visibleModules();
            ModuleCategory[] categories = ModuleCategory.values();
            int categoryPages = pageCount(categories.length, layout.categorySlots());
            categoryPage = Math.max(0, Math.min(categoryPage, categoryPages - 1));
            int modulePages = pageCount(modules.size(), layout.perPage());
            modulePage = Math.max(0, Math.min(modulePage, modulePages - 1));

            int footerY = y + h - layout.footerHeight();

            // Full-screen dim and a compact, high-contrast panel.
            ctx.fill(0, 0, width, height, 0xC8000000);
            ctx.fill(x, y, x + w, y + h, 0xF014131D);
            ctx.fill(x, y, x + w, y + 2, 0xFFB46CFF);
            ctx.fill(x + 8, y + 43, x + w - 8, y + 44, 0xFF383241);

            // DIPX identity and live registry status.
            ctx.drawText(textRenderer, "DIPX", x + 12, y + 9, 0xFFE9D8FF, false);
            ctx.drawText(textRenderer, "ALL-ROUNDER  /  PVP CLIENT",
                    x + 12, y + 24, 0xFFAAA0B9, false);
            String registryStatus = all.size() + " MODULES  /  " + ModuleRegistry.enabledCount() + " ON";
            int statusWidth = textRenderer.getWidth(registryStatus);
            ctx.drawText(textRenderer, registryStatus,
                    x + w - statusWidth - 12, y + 15, 0xFFB98BFF, false);

            // Sidebar.
            int sidebarRight = x + layout.sidebarWidth();
            ctx.fill(x + 8, y + 49, sidebarRight, footerY - 4, 0xFF1D1A27);
            ctx.drawText(textRenderer, "CATEGORIES", x + 13, y + 53, 0xFF8E849F, false);

            int firstCategory = categoryPage * layout.categorySlots();
            int lastCategory = Math.min(firstCategory + layout.categorySlots(), categories.length);
            int categoryTop = y + layout.categoryTop();
            for (int i = firstCategory; i < lastCategory; i++) {
                int row = i - firstCategory;
                int cy = categoryTop + row * CATEGORY_ROW;
                boolean active = categories[i] == selected;
                if (active) {
                    ctx.fill(x + 10, cy - 3, sidebarRight - 4, cy + 15, 0xFF563477);
                    ctx.fill(x + 10, cy - 3, x + 12, cy + 15, 0xFFCB8DFF);
                }
                String label = textRenderer.trimToWidth(
                        categories[i].title, Math.max(1, layout.sidebarWidth() - 25));
                ctx.drawText(textRenderer, label, x + 16, cy + 1,
                        active ? 0xFFFFFFFF : 0xFFB9B2C6, false);
            }

            if (categoryPages > 1) {
                String categoryPageLabel = "CAT " + (categoryPage + 1) + "/" + categoryPages;
                ctx.drawText(textRenderer, categoryPageLabel, x + 13, footerY + 5,
                        0xFFB98BFF, false);
            }

            // Main content header.
            int contentX = x + layout.contentX();
            int contentWidth = layout.contentWidth();
            String categoryTitle = selected.title.toUpperCase(Locale.ROOT);
            ctx.drawText(textRenderer, categoryTitle, contentX, y + 50, 0xFFE9D8FF, false);
            String categorySummary = modules.size() + " AVAILABLE";
            int summaryWidth = textRenderer.getWidth(categorySummary);
            if (contentWidth > summaryWidth + 8) {
                ctx.drawText(textRenderer, categorySummary,
                        contentX + contentWidth - summaryWidth, y + 50, 0xFF91889F, false);
            }

            // Touch-friendly module cards. Every visible card maps to the same
            // hitbox calculation in mouseClicked(), avoiding render/input drift.
            int cardTop = y + layout.contentTop();
            int start = modulePage * layout.perPage();
            int end = Math.min(start + layout.perPage(), modules.size());
            if (modules.isEmpty()) {
                ctx.drawText(textRenderer, "NO MODULES IN THIS CATEGORY",
                        contentX + 6, cardTop + 8, 0xFFB9B2C6, false);
            }

            for (int i = start; i < end; i++) {
                int local = i - start;
                int column = local % layout.columns();
                int row = local / layout.columns();
                int bx = contentX + column * (layout.cardWidth() + CARD_GAP);
                int by = cardTop + row * (layout.cardHeight() + layout.rowGap());
                DIPXModule module = modules.get(i);
                boolean enabled = module.enabled();

                ctx.fill(bx, by, bx + layout.cardWidth(), by + layout.cardHeight(),
                        enabled ? 0xFF382648 : 0xFF25232E);
                ctx.fill(bx, by, bx + 2, by + layout.cardHeight(),
                        enabled ? 0xFFCB8DFF : 0xFF625A6D);

                String moduleName = textRenderer.trimToWidth(
                        module.name(), Math.max(1, layout.cardWidth() - 14));
                String description = textRenderer.trimToWidth(
                        module.description(), Math.max(1, layout.cardWidth() - 14));
                ctx.drawText(textRenderer, moduleName, bx + 7, by + 5, 0xFFF3EDFA, false);
                if (layout.cardHeight() >= 30) {
                    ctx.drawText(textRenderer, description, bx + 7, by + 18, 0xFFAAA1B7, false);
                }

                int pillWidth = Math.min(34, Math.max(22, layout.cardWidth() / 3));
                int pillX = bx + layout.cardWidth() - pillWidth - 6;
                int pillY = by + layout.cardHeight() - 15;
                if (pillY >= by + 2 && pillY + 11 <= by + layout.cardHeight()) {
                    ctx.fill(pillX, pillY, pillX + pillWidth, pillY + 11,
                            enabled ? 0xFF70439A : 0xFF37333F);
                    ctx.drawText(textRenderer, enabled ? "ON" : "OFF",
                            pillX + 5, pillY + 2,
                            enabled ? 0xFFFFFFFF : 0xFFB2AABD, false);
                }
            }

            String footer = "ACTIVE " + ModuleRegistry.enabledCount() + "/" + all.size()
                    + "   •   PAGE " + (modulePage + 1) + "/" + modulePages + "   •   TAP TO NEXT";
            ctx.drawText(textRenderer, textRenderer.trimToWidth(footer, Math.max(1, contentWidth)),
                    contentX, footerY + 5, 0xFF9E95AA, false);
        } catch (RuntimeException renderError) {
            // Keep the screen alive after recoverable renderer errors, but retain
            // the full stack trace in latest.log instead of silently hiding it.
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("DIPX GUI render failed; screen retained for diagnosis", renderError);
            }
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        DIPXLayout layout = DIPXLayout.calculate(width, height);
        int x = layout.panelX();
        int y = layout.panelY();
        int h = layout.panelHeight();
        int sidebarRight = x + layout.sidebarWidth();
        int footerY = y + h - layout.footerHeight();
        double mouseX = click.x();
        double mouseY = click.y();

        ModuleCategory[] categories = ModuleCategory.values();
        int firstCategory = categoryPage * layout.categorySlots();
        int lastCategory = Math.min(firstCategory + layout.categorySlots(), categories.length);
        int categoryTop = y + layout.categoryTop();

        if (mouseX >= x + 10 && mouseX <= sidebarRight - 4
                && mouseY >= categoryTop - 3
                && mouseY < categoryTop + (lastCategory - firstCategory) * CATEGORY_ROW) {
            int row = (int) ((mouseY - categoryTop) / CATEGORY_ROW);
            int index = firstCategory + row;
            if (index >= firstCategory && index < lastCategory) {
                selected = categories[index];
                modulePage = 0;
                return true;
            }
        }

        int categoryPages = pageCount(categories.length, layout.categorySlots());
        if (categoryPages > 1 && mouseX >= x + 8 && mouseX <= sidebarRight
                && mouseY >= footerY && mouseY <= y + h) {
            categoryPage = (categoryPage + 1) % categoryPages;
            return true;
        }

        List<DIPXModule> modules = visibleModules();
        int pages = pageCount(modules.size(), layout.perPage());
        int contentX = x + layout.contentX();
        int cardTop = y + layout.contentTop();
        int start = modulePage * layout.perPage();
        int end = Math.min(start + layout.perPage(), modules.size());

        for (int i = start; i < end; i++) {
            int local = i - start;
            int column = local % layout.columns();
            int row = local / layout.columns();
            int bx = contentX + column * (layout.cardWidth() + CARD_GAP);
            int by = cardTop + row * (layout.cardHeight() + layout.rowGap());
            if (mouseX >= bx && mouseX <= bx + layout.cardWidth()
                    && mouseY >= by && mouseY <= by + layout.cardHeight()) {
                modules.get(i).toggle();
                return true;
            }
        }

        if (mouseX >= contentX && mouseX <= contentX + layout.contentWidth()
                && mouseY >= footerY && mouseY <= y + h) {
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
