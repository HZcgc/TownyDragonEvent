package net.townysmp.dragonevent;

import org.bukkit.*;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Real Paper interfaces with isolated world, scheduler and display doubles. */
class DragonLifecycleDisplaysTest {
 @TempDir Path root;
 static Fixture current;
 static Server server;
 @BeforeAll static void server() throws Exception {
  server=proxy(Server.class,(p,m,a)->current==null?(m.getName().equals("getLogger")?Logger.getLogger("Fixture"):"Paper fixture"):current.server(m.getName(),a));
  Field field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);field.set(null,server);
 }
 @BeforeEach void setup() throws Exception { current=new Fixture(root); }

 @Test void fourBoardsAreFiveAndAHalfBlocksOutsideEachPortalEdge() throws Exception {
  World arena=current.world("arena",World.Environment.THE_END);EventDisplays displays=new EventDisplays(current.plugin);
  displays.update(arena,new Location(arena,0,60,0),EventState.JOINING,300,0,List.of("Live player"),List.of("Season player"),List.of("Lifetime player"),List.of("Winning player"));
  assertEquals(4,current.boards.size());
  for(Board b:current.boards){assertEquals(5.5,Math.hypot(b.location.getX(),b.location.getZ())-3,.000001);assertEquals(64.5,b.location.getY(),.000001);assertTrue(b.invulnerable);assertFalse(b.persistent);}
  assertTrue(current.boss.visible);assertEquals(1,current.boss.progress);assertTrue(current.boss.title.contains("05:00"));
  displays.update(arena,new Location(arena,0,60,0),EventState.ACTIVE,0,0,List.of("New damage"),List.of(),List.of(),List.of());
  assertEquals(4,current.boards.size());assertFalse(current.boss.visible);assertTrue(current.boards.getFirst().text.toString().contains("New damage"));
  displays.clear();assertTrue(current.boards.stream().noneMatch(b->b.valid));
 }
 @Test void displayHeightAndDistanceAreConfigurable() {
  current.config.set("displays.distance-from-portal",6.0);current.config.set("displays.height",7.0);
  World arena=current.world("arena",World.Environment.THE_END);
  new EventDisplays(current.plugin).update(arena,new Location(arena,10,50,-10),EventState.JOINING,120,0,List.of(),List.of(),List.of(),List.of());
  for(Board b:current.boards){assertEquals(9,Math.hypot(b.location.getX()-10,b.location.getZ()+10),.000001);assertEquals(57,b.location.getY(),.000001);}
  assertTrue(current.boss.title.contains("02:00"));assertEquals(.4,current.boss.progress,.000001);
 }
 @Test void unmarkedLegacyRecoveryReturnsToIdleWithoutDeletingTheFolder() throws Exception {
  Path legacy=current.namespace.resolve("dragonevent_end");Files.createDirectories(legacy);Files.writeString(legacy.resolve("arena.dat"),"keep");
  current.world("dragonevent_end",World.Environment.THE_END);
  DragonEventManager manager=new DragonEventManager(current.plugin,null,null);manager.recoverAfterRestart();
  waitIdle(manager);manager.stop();assertEquals(EventState.IDLE,manager.state());
  assertEquals("keep",Files.readString(legacy.resolve("arena.dat")));assertEquals(1,current.unloads);
 }
 @Test void markedRecoveryDeletesOnlyTheClone() throws Exception {
  Path template=current.namespace.resolve("dragonevent_template"),runtime=current.namespace.resolve("dragonevent_end");Files.createDirectories(template);Files.writeString(template.resolve("arena.dat"),"template");WorldFiles.copy(root,template,runtime);
  DragonEventManager manager=new DragonEventManager(current.plugin,null,null);manager.recoverAfterRestart();waitIdle(manager);
  assertFalse(Files.exists(runtime));assertEquals("template",Files.readString(template.resolve("arena.dat")));
 }
 @Test void unsafeRecoveryAndRepeatedStopCannotDeleteTemplate() throws Exception {
  current.config.set("world.runtime-world","dragonevent_template");current.config.set("world.runtime-name","dragonevent_template");
  Path template=current.namespace.resolve("dragonevent_template");Files.createDirectories(template);Files.writeString(template.resolve("arena.dat"),"keep template");
  DragonEventManager manager=new DragonEventManager(current.plugin,null,null);manager.recoverAfterRestart();manager.stop();
  assertEquals(EventState.RESETTING,manager.state());assertEquals("keep template",Files.readString(template.resolve("arena.dat")));assertEquals(0,current.unloads);
 }
 static void waitIdle(DragonEventManager manager){assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(manager.state()!=EventState.IDLE)Thread.sleep(5);});}
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
  if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("toString"))return type.getSimpleName()+" fixture";
  Object result=handler.invoke(p,m,a==null?new Object[0]:a);if(result==null&&m.getReturnType().isPrimitive()){if(m.getReturnType()==boolean.class)return false;if(m.getReturnType()==double.class)return 0.0;if(m.getReturnType()==float.class)return 0f;if(m.getReturnType()==long.class)return 0L;return 0;}return result;});}
 static void set(Object value,String field,Object data)throws Exception{Field f=JavaPlugin.class.getDeclaredField(field);f.setAccessible(true);f.set(value,data);}
 static class Board {Location location;boolean valid=true,persistent=true,invulnerable;Object text;Set<String>tags=new HashSet<>();TextDisplay display;}
 static class Bar {String title="";double progress;boolean visible;List<Player>players=new ArrayList<>();BossBar bar=proxy(BossBar.class,(p,m,a)->{switch(m.getName()){case "getPlayers":return List.copyOf(players);case "setTitle":title=(String)a[0];break;case "setProgress":progress=(double)a[0];break;case "setVisible":visible=(boolean)a[0];break;case "removeAll":players.clear();break;case "addPlayer":if(!players.contains(a[0]))players.add((Player)a[0]);break;case "removePlayer":players.remove(a[0]);break;}return null;});}
 static class Fixture {
  final Path root,namespace;final YamlConfiguration config=new YamlConfiguration();final TownyDragonEventPlugin plugin;final Map<String,World> worlds=new HashMap<>();final List<Board>boards=new ArrayList<>();final Bar boss=new Bar();int unloads;
  Fixture(Path root)throws Exception{
   this.root=root;namespace=root.resolve("earth/dimensions/minecraft");Files.createDirectories(namespace);world("overworld",World.Environment.NORMAL);
   Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);plugin=(TownyDragonEventPlugin)((sun.misc.Unsafe)f.get(null)).allocateInstance(TownyDragonEventPlugin.class);
   set(plugin,"server",server);set(plugin,"logger",Logger.getLogger("DragonFixture"));set(plugin,"newConfig",config);set(plugin,"dataFolder",root.resolve("plugins/Dragon").toFile());Files.createDirectories(plugin.getDataFolder().toPath());
   set(plugin,"isEnabled",true);
   config.set("world.runtime-world","dragonevent_end");config.set("world.runtime-name","dragonevent_end");config.set("world.template-world","dragonevent_template");config.set("world.template-name","dragonevent_template");
  }
  Object server(String method,Object[]a){return switch(method){case "getLogger"->Logger.getLogger("Fixture");case "getName","getVersion","getBukkitVersion"->"Paper fixture";case "getWorldContainer"->root.toFile();case "getWorlds"->List.copyOf(worlds.values());case "getWorld"->worlds.get(a[0]);case "isPrimaryThread"->true;case "createBossBar"->boss.bar;case "unloadWorld"->{unloads++;worlds.values().remove(a[0]);yield true;}case "getScheduler"->proxy(BukkitScheduler.class,(p,m,b)->{if(b.length>1&&b[1] instanceof Runnable r)r.run();return proxy(BukkitTask.class,(x,y,z)->null);});default->null;};}
  World world(String name,World.Environment environment){World w=proxy(World.class,(p,m,a)->switch(m.getName()){
   case "getName"->name;case "getWorldFolder"->namespace.resolve(name).toFile();case "getEnvironment"->environment;case "getPlayers"->List.of();case "getEntities"->boards.stream().filter(b->b.valid).map(b->(Entity)b.display).toList();case "spawn"->{Board b=new Board();b.location=((Location)a[0]).clone();b.display=proxy(TextDisplay.class,(x,y,z)->{switch(y.getName()){case "isValid":return b.valid;case "remove":b.valid=false;break;case "getScoreboardTags":return b.tags;case "addScoreboardTag":return b.tags.add((String)z[0]);case "setPersistent":b.persistent=(boolean)z[0];break;case "setInvulnerable":b.invulnerable=(boolean)z[0];break;case "text":if(z.length>0)b.text=z[0];else return b.text;break;}return null;});boards.add(b);@SuppressWarnings("unchecked") Consumer<TextDisplay> callback=(Consumer<TextDisplay>)a[a.length-1];callback.accept(b.display);yield b.display;}default->null;});worlds.put(name,w);return w;}
 }
}
