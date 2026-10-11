package net.townysmp.dragonevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import java.util.*;

/** Native Paper text displays; no additional hologram plugin required. */
final class EventDisplays {
    private final TownyDragonEventPlugin plugin;
    private final BossBar lobby;
    private final List<TextDisplay> boards=new ArrayList<>();
    private final List<String> previous=new ArrayList<>();
    private static final String TAG="townyreborn_dragon_board";
    EventDisplays(TownyDragonEventPlugin plugin){this.plugin=plugin;lobby=Bukkit.createBossBar("Towny Reborn · Dragon lobby",BarColor.PINK,BarStyle.SOLID);}
    void update(World world,Location center,EventState state,int seconds,int players,List<String> live,List<String> season,List<String> allTime,List<String> wins){
        if(world==null||center==null||state==EventState.IDLE||state==EventState.RESETTING){clear();return;}
        boolean joining=state==EventState.JOINING;
        if(joining&&plugin.getConfig().getBoolean("displays.lobby-countdown",true)){
            String time=String.format(Locale.ROOT,"%02d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60);
            lobby.setTitle("Towny Reborn · Dragon starts in "+time+" · "+players+" players");
            lobby.setProgress(Math.max(0,Math.min(1,(double)seconds/Math.max(1,plugin.getConfig().getInt("event.join-seconds",300)))));
            Set<Player> visible=new HashSet<>(world.getPlayers());
            for(Player p:new ArrayList<>(lobby.getPlayers()))if(!visible.contains(p))lobby.removePlayer(p);
            for(Player p:visible){lobby.addPlayer(p);if(plugin.getConfig().getBoolean("displays.lobby-actionbar",true))p.sendActionBar(Component.text("Dragon starts in "+time,NamedTextColor.LIGHT_PURPLE));}
            lobby.setVisible(true);
        }else{lobby.removeAll();lobby.setVisible(false);}
        if(!plugin.getConfig().getBoolean("displays.portal-leaderboards",true)){removeBoards();return;}
        if(boards.stream().anyMatch(b->!b.isValid()))removeBoards();
        if(boards.isEmpty()){
            for(Entity e:world.getEntities())if(e.getScoreboardTags().contains(TAG))e.remove();
            double radius=Math.max(3,Math.min(30,plugin.getConfig().getDouble("displays.portal-radius",3.0)+plugin.getConfig().getDouble("displays.distance-from-portal",5.5)));
            double y=plugin.getConfig().getDouble("displays.height",4.5);
            for(int i=0;i<4;i++){
                double angle=Math.toRadians(i*90);Location location=center.clone().add(Math.cos(angle)*radius,y,Math.sin(angle)*radius);
                TextDisplay display=world.spawn(location,TextDisplay.class,d->{
                    d.setBillboard(Display.Billboard.CENTER);d.setPersistent(false);d.setGravity(false);d.setInvulnerable(true);d.addScoreboardTag(TAG);
                    d.setShadowed(true);d.setSeeThrough(false);d.setBackgroundColor(Color.fromARGB(210,12,16,28));
                    d.setLineWidth(260);d.setViewRange((float)Math.max(.25,Math.min(4,plugin.getConfig().getDouble("displays.view-distance",64)/64)));
                });boards.add(display);previous.add("");
            }
        }
        List<List<String>> lists=List.of(live,season,allTime,wins);String[] titles={"LIVE DRAGON DAMAGE","SEASON DAMAGE","ALL-TIME DAMAGE","ALL-TIME WINS"};
        for(int i=0;i<boards.size();i++){
            String text=String.join("\n",lists.get(i));String status=joining?"Lobby · "+String.format(Locale.ROOT,"%02d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60):state.name().toLowerCase(Locale.ROOT);
            String full=titles[i]+"\n"+status+"\n"+text;
            if(!full.equals(previous.get(i))&&boards.get(i).isValid()){
                boards.get(i).text(Component.text("Towny Reborn\n",NamedTextColor.LIGHT_PURPLE).append(Component.text(titles[i]+"\n",NamedTextColor.GREEN)).append(Component.text(status+"\n",NamedTextColor.GRAY)).append(Component.text(text.isEmpty()?"No results yet":text,NamedTextColor.WHITE)));previous.set(i,full);
            }
        }
    }
    private void removeBoards(){boards.forEach(Entity::remove);boards.clear();previous.clear();}
    void clear(){lobby.removeAll();lobby.setVisible(false);removeBoards();}
}
