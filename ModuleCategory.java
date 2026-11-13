package com.dipx.client.module;

public enum ModuleCategory {
    CRYSTAL_PVP("Combat"),
    COMBAT("Combat"),
    MACE_PVP("Mace PvP"),
    SPEAR_PVP("Spear PvP"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    PLAYER("Player");
    public final String title;
    ModuleCategory(String title) { this.title = title; }
}
