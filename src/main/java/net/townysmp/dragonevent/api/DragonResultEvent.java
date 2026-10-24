package net.townysmp.dragonevent.api;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class DragonResultEvent
extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String eventId;
    private final String playerName;
    private final UUID playerId;
    private final double damage;
    private final int rank;
    private final int deaths;
    private final int crystals;

    public DragonResultEvent(String eventId, UUID playerId, String playerName, double damage, int rank, int deaths, int crystals) {
        this.eventId = eventId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.damage = damage;
        this.rank = rank;
        this.deaths = deaths;
        this.crystals = crystals;
    }

    public String getEventId() {
        return this.eventId;
    }

    public UUID getPlayerId() {
        return this.playerId;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public double getDamage() {
        return this.damage;
    }

    public int getRank() {
        return this.rank;
    }

    public int getDeaths() {
        return this.deaths;
    }

    public int getCrystals() {
        return this.crystals;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

