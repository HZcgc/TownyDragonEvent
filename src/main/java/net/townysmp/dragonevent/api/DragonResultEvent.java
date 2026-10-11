package net.townysmp.dragonevent.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** One completed result per rewarded participant. Consumers must deduplicate eventId + playerId. */
public final class DragonResultEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final String eventId,playerName;
    private final UUID playerId;
    private final double damage;
    private final int rank,deaths,crystals;
    public DragonResultEvent(String eventId,UUID playerId,String playerName,double damage,int rank,int deaths,int crystals){this.eventId=eventId;this.playerId=playerId;this.playerName=playerName;this.damage=damage;this.rank=rank;this.deaths=deaths;this.crystals=crystals;}
    public String getEventId(){return eventId;}
    public UUID getPlayerId(){return playerId;}
    public String getPlayerName(){return playerName;}
    public double getDamage(){return damage;}
    public int getRank(){return rank;}
    public int getDeaths(){return deaths;}
    public int getCrystals(){return crystals;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
