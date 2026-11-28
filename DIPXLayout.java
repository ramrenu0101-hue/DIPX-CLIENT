package com.dipx.client.gui;

/**
 * Pure geometry for the DIPX screen. Kept separate from Minecraft rendering so
 * the responsive layout can be regression-tested without starting a game.
 */
public record DIPXLayout(
        int panelX,
        int panelY,
        int panelWidth,
        int panelHeight,
        int sidebarWidth,
        int contentX,
        int contentWidth,
        int columns,
        int cardWidth,
        int cardHeight,
        int rowGap,
        int contentTop,
        int footerHeight,
        int perPage,
        int categoryTop,
        int categorySlots
) {
    public static DIPXLayout calculate(int screenWidth, int screenHeight) {
        int safeWidth = Math.max(1, screenWidth);
        int safeHeight = Math.max(1, screenHeight);

        int panelWidth = Math.max(1, Math.min(470, safeWidth - 12));
        int panelHeight = Math.max(1, Math.min(292, safeHeight - 12));
        int panelX = (safeWidth - panelWidth) / 2;
        int panelY = (safeHeight - panelHeight) / 2;

        int sidebarWidth = Math.max(1, Math.min(116, panelWidth / 3));
        int contentX = Math.min(panelWidth, sidebarWidth + 14);
        int contentWidth = Math.max(0, panelWidth - contentX - 10);
        int columns = contentWidth >= 150 ? 2 : 1;
        int cardGap = 6;
        int cardWidth = Math.max(1, (contentWidth - cardGap * (columns - 1)) / columns);

        int contentTop = Math.min(68, Math.max(44, panelHeight / 4));
        int footerHeight = Math.min(24, Math.max(16, panelHeight / 7));
        int availableHeight = Math.max(0, panelHeight - contentTop - footerHeight - 8);
        int cardHeight = Math.max(1, Math.min(46, availableHeight));
        int rowGap = 7;
        int rows = Math.max(1, (availableHeight + rowGap) / (cardHeight + rowGap));
        int perPage = Math.max(1, rows * columns);

        int categoryTop = Math.min(68, Math.max(48, panelHeight / 4));
        int categoryAvailable = Math.max(0, panelHeight - categoryTop - footerHeight - 8);
        int categorySlots = Math.max(1, categoryAvailable / 20);

        return new DIPXLayout(
                panelX, panelY, panelWidth, panelHeight,
                sidebarWidth, contentX, contentWidth, columns, cardWidth,
                cardHeight, rowGap, contentTop, footerHeight, perPage,
                categoryTop, categorySlots
        );
    }
}
