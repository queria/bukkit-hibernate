package com.woutwoot.hibernate;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * When there are no players connected, use BukkitRunnable to ask for Thread.sleep
 * which practically hangs the server (like it would be overloaded by plugin) while not consuming cpu.
 *
 * Achieved by having two BukkitRunnable tasks:
 *
 * - first (called watcher) runs with lower frequency and checks
 *   if the conditions for hibernating are meant
 *   (lower frequency to not be involved much during gameplay)
 *   if conditions are matching then spawn the 'freezing' task
 *
 * - freezing task runs very often (every tick or few)
 *   and requests sleeping, stops itself if conditions change
 *
 * - first run of hibernation task can unload world chunks
 *   to minimaze the resources needed (optional)
 *   (wonder if it has any effect in OS e.g. with Xms==Xmx and without explicitly asking gc)
 */
public class Main extends JavaPlugin {

  Logger log;

  private boolean enabled = true;
  private BukkitTask task;
  private BukkitTask watcher;

  private boolean unloadChunksFirst = true;
  private long hibernationTimeMillis = 1500L;
  private int hibernationDelayTicks = 5*20; // delay by 5 sec (TODO: this should be higher for production, like minute or two)
  private int hibernationFrequencyTicks = 2;
  private int onlineCheckFrequencyTicks = 30*20; // check every half minute

  @Override
  public void onEnable() {
    log = getLogger();

    startWatcher();
  }

  @Override
  public void onDisable() {
    stopWatcher();
    stopTask();
  }

  @Override
  public boolean onCommand(final CommandSender sender, final Command command, final String label,
      final String[] args) {
    if (command.getName().equalsIgnoreCase("hibernate") && (sender.isOp() || sender
        .hasPermission("hibernate.toggle"))) {
      sender.sendMessage(
          "[Hibernate] Hibernate is now " + (this.toggleEnabled() ? "enabled" : "disabled"));
      return true;
    }
    return false;
  }

  private boolean toggleEnabled() {
    this.enabled = !this.enabled;
    return this.enabled;
  }

  private boolean shouldHibernate() {
    return (this.enabled && Bukkit.getServer().getOnlinePlayers().isEmpty());
  }

  private void startWatcher() {

    log.info("Starting watcher");

    watcher = (new BukkitRunnable() {

      @Override
      public void run() {
        if (!Main.this.shouldHibernate()) {
          // ensure task is not running
          // (likely we do not need this here
          //  as hibernation task will stop itself
          //  when conditions change [as it's running often unlike us here]
          //  but keeping it here for now
          //  otherwise if(shouldHibernate){ startTask } could be enough
          Main.this.stopTask();
          return;
        }
        // no players and enabled
        Main.this.startTask();
      }
    }).runTaskTimer(
      this,
      this.hibernationDelayTicks,
      this.onlineCheckFrequencyTicks);
  }

  private void stopWatcher() {
    if (watcher != null && !watcher.isCancelled()) {
      log.info("Stopping watcher");
      watcher.cancel();
    }
  }

  private void unloadChunks() {
    log.info("Unloading chunks");
    for (final World w : Bukkit.getWorlds()) {
      for (final Chunk c : w.getLoadedChunks()) {
        c.unload(true);
      }
    }
  }

  private void startTask() {
    if (task != null && !task.isCancelled()) {
      return;
    }

    log.info("Starting hibernation task");

    task = (new BukkitRunnable() {
      private boolean firstRun = true;

      @Override
      public void run() {
        if (!Main.this.shouldHibernate()) {
          // if situation changed
          // do just nothing here
          // and abort
          Main.this.stopTask();
          return;
        }
        if (firstRun) {
          firstRun = false;
          if (Main.this.unloadChunksFirst) {
            Main.this.unloadChunks();
          }
        }

        try {
          Thread.sleep(Main.this.hibernationTimeMillis);
        } catch (Exception ignored) {
          // IGNORED
        }
      }
    }).runTaskTimer(
      this,
      this.hibernationDelayTicks,
      this.hibernationFrequencyTicks);
  }

  private void stopTask() {
    if (task != null && !task.isCancelled()) {
      log.info("Stopping hibernation task");
      task.cancel();
    }
  }

}
