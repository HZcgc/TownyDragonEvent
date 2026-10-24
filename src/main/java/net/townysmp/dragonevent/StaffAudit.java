package net.townysmp.dragonevent;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Mode and reward-command audit, independent of the legacy damage/win reader. */
public final class StaffAudit {
    private static Plugin connector;
    private static Method sink;
    private static final Map<String,Set<String>> transitions=new LinkedHashMap<>();
    private static final Map<String,Long> fightStarts=new HashMap<>();
    private static Object field(Object o,String name)throws ReflectiveOperationException {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Map<String,Object> context(Object manager)throws ReflectiveOperationException {
        Map<String,Object> d=new LinkedHashMap<>();d.put("event_id",Objects.toString(field(manager,"resultEventId"),""));d.put("mode",String.valueOf(field(manager,"eventMode")).toLowerCase(Locale.ROOT));return d;
    }
    public static void transition(Object manager,String metric,boolean victory,String reason) {
        try {
            Map<String,Object> d=context(manager);String id=(String)d.get("event_id");if(id.isEmpty())return;
            Set<String> seen=transitions.computeIfAbsent(id,k->new HashSet<>());if(!seen.add(metric))return;
            if(transitions.size()>16){String old=transitions.keySet().iterator().next();transitions.remove(old);fightStarts.remove(old);}
            if(metric.equals("dragon_fights_started"))fightStarts.put(id,System.currentTimeMillis());
            d.put("victory",victory);d.put("reason",reason);d.put("participants",((Set<?>)field(manager,"participants")).size());d.put("effective_max_hp",field(manager,"effectiveDragonHealth"));
            if(fightStarts.containsKey(id))d.put("fight_duration_ms",Math.max(0,System.currentTimeMillis()-fightStarts.get(id)));
            publish(metric,null,"",1,String.valueOf(d.get("mode")),d);
        }catch(ReflectiveOperationException|RuntimeException ignored){}
    }
    public static void result(Object manager,UUID uuid,String name,double damage,int rank,int deaths,int crystals,int explosions,boolean victory) {
        try {
            Map<String,Object> d=context(manager);d.put("damage",damage);d.put("rank",rank);d.put("deaths",deaths);d.put("crystals",crystals);d.put("explosions",explosions);d.put("victory",victory);
            publish("dragon_results",uuid,name,1,String.valueOf(d.get("mode")),d);
        }catch(ReflectiveOperationException|RuntimeException ignored){}
    }
    public static void reward(Object manager,UUID uuid,String name,String path,String command,boolean accepted) {
        try {
            Map<String,Object> d=context(manager);d.put("reward_path",path);d.put("command",command);d.put("dispatch_accepted",accepted);d.put("basis","command_dispatch_not_provider_receipt");
            publish("dragon_reward_commands",uuid,name,1,String.valueOf(d.get("mode")),d);
            if(!accepted)return;
            String[] a=command.trim().replaceFirst("^/","").split("\\s+");
            if(a.length==4&&(a[0].equalsIgnoreCase("eco")||a[0].equalsIgnoreCase("essentials:eco"))&&a[1].equalsIgnoreCase("give")&&a[2].equalsIgnoreCase(name)){
                double amount=Double.parseDouble(a[3]);if(Double.isFinite(amount)&&amount>0)publish("dragon_money_commands",uuid,name,amount,String.valueOf(d.get("mode")),d);
            }
            if(a.length==5&&(a[0].equalsIgnoreCase("cratekey")||a[0].equalsIgnoreCase("excellentcrates:cratekey"))&&a[1].equalsIgnoreCase("give")&&a[2].equalsIgnoreCase(name)){
                int count=Integer.parseInt(a[4]);if(count>0)publish("dragon_key_commands",uuid,name,count,a[3],d);
            }
        }catch(ReflectiveOperationException|RuntimeException ignored){}
    }
    public static Map<String,Object> snapshot(JavaPlugin plugin)throws ReflectiveOperationException {
        Object manager=field(plugin,"eventManager");Map<String,Object> d=context(manager);d.put("audit_version",1);d.put("plugin_version",plugin.getPluginMeta().getVersion());
        String state=String.valueOf(field(manager,"state"));d.put("phase",state);d.put("participants",((Set<?>)field(manager,"participants")).size());d.put("spectators",((Set<?>)field(manager,"spectators")).size());d.put("enraged",field(manager,"enraged"));d.put("effective_max_hp",field(manager,"effectiveDragonHealth"));
        d.put("seconds_remaining",field(manager,state.equals("ACTIVE")?"fightSecondsLeft":state.equals("FINISHING")?"closingSecondsLeft":"secondsLeft"));
        Object boss=field(manager,"dragon");if(boss instanceof LivingEntity entity&&entity.isValid())d.put("native_hp",entity.getHealth());
        d.put("board_count",4);return d;
    }
    private static void publish(String metric,UUID uuid,String name,double value,String dimension,Map<String,Object> details){
        try{
            Plugin p=Bukkit.getPluginManager().getPlugin("TownyRebornStaffConnector");if(p==null||!p.isEnabled())return;
            if(p!=connector){sink=Class.forName("com.townyreborn.staffconnector.ExpandedGameplay",true,p.getClass().getClassLoader()).getMethod("accept",Map.class);connector=p;}
            Map<String,Object> row=new LinkedHashMap<>();row.put("plugin","TownyDragonEvent");row.put("metric",metric);row.put("uuid",uuid==null?"":uuid.toString());row.put("username",name);row.put("value",value);row.put("dimension",dimension);row.put("details",details);row.put("occurred_at",Instant.now().toString());sink.invoke(null,row);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){}
    }
}
