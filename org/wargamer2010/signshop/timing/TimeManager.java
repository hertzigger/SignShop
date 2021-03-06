
package org.wargamer2010.signshop.timing;

import org.wargamer2010.signshop.events.SSExpiredEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.wargamer2010.signshop.SignShop;
import org.wargamer2010.signshop.events.SSEventFactory;

public class TimeManager extends TimerTask {
    private static final int interval = 1000; // in ms
    private static final int saveinterval = 10000;
    private int intervalcount = 0;
    private Map<IExpirable, Integer> timeByExpirable = new LinkedHashMap<IExpirable, Integer>();
    private ReentrantLock timerLock = new ReentrantLock();
    private File storageFile = null;
    private YamlConfiguration storageConfiguration = null;

    public TimeManager(File storage) {
        storageFile = storage;
        if(storage.exists()) {
            YamlConfiguration yml = new YamlConfiguration();
            try {
                yml.load(storage);
                storageConfiguration = yml;
                HashMap<String, HashMap<String, String>> entries = fetchHasmapInHashmap("expirables", yml);
                for(Map.Entry<String, HashMap<String, String>> entry : entries.entrySet()) {
                    Object ob = tryReflection(removeTrailingCounter(entry.getKey()));
                    if(ob != null && ob instanceof IExpirable) {
                        IExpirable expirable = (IExpirable)ob;
                        if(entry.getValue().containsKey("_timeleft")) {
                            try {
                                String left = entry.getValue().get("_timeleft");
                                Integer iLeft = Integer.parseInt(left);
                                if(expirable.parseEntry(entry.getValue())) {
                                    timeByExpirable.put(expirable, iLeft);
                                } else {
                                    SignShop.log("Could not run parse for : " + entry.getKey(), Level.WARNING);
                                    continue;
                                }
                            } catch(NumberFormatException ex) {
                                SignShop.log("Invalid _timeleft value detected: " + entry.getValue().get("_timeleft"), Level.WARNING);
                                continue;
                            }
                        } else {
                            SignShop.log("Could not find _timeleft property for : " + removeTrailingCounter(entry.getKey()), Level.WARNING);
                            continue;
                        }
                    } else {
                        SignShop.log(removeTrailingCounter(entry.getKey()) + " is not an IExpirable so cannot load it", Level.WARNING);
                        continue;
                    }
                }
            } catch (FileNotFoundException ex) {
                SignShop.log("Unable to load " + storage.getAbsolutePath() + " because: " + ex.getMessage(), Level.SEVERE);
                return;
            } catch (IOException ex) {
                SignShop.log("Unable to load " + storage.getAbsolutePath() + " because: " + ex.getMessage(), Level.SEVERE);
                return;
            } catch (InvalidConfigurationException ex) {
                SignShop.log("Unable to load " + storage.getAbsolutePath() + " because: " + ex.getMessage(), Level.SEVERE);
                return;
            }
        } else {
            try {
                storage.createNewFile();
                storageFile = storage;
                storageConfiguration = new YamlConfiguration();
            } catch (IOException ex) {
                SignShop.log("Unable to create " + storage.getAbsolutePath() + " because: " + ex.getMessage(), Level.SEVERE);
                return;
            }
        }
        scheduleCheck();
    }

    public void addExpirable(IExpirable pExpirable, Integer seconds) {
        if(!timeByExpirable.containsKey(pExpirable))
            timeByExpirable.put(pExpirable, seconds);
    }

    public boolean removeExpirable(Map<String, String> descriptor) {
        IExpirable toremove = null;
        for(IExpirable exp : timeByExpirable.keySet()) {
            if(exp.getEntry().equals(descriptor)) {
                toremove = exp;
            }
        }
        if(toremove != null) {
            timeByExpirable.remove(toremove);
            return true;
        }
        return false;
    }

    @Override
    public void run() {
        timerLock.lock();

        try {
            Map<IExpirable, Integer> update = new LinkedHashMap<IExpirable, Integer>();
            for(Map.Entry<IExpirable, Integer> entry : timeByExpirable.entrySet()) {
                Integer left = (entry.getValue() - getSeconds(interval));
                if(left == 0) {
                    SSExpiredEvent event = SSEventFactory.generateExpiredEvent(entry.getKey());
                    Bukkit.getServer().getPluginManager().callEvent(event);
                } else {
                    update.put(entry.getKey(), left);
                }
            }
            timeByExpirable.clear();
            for(Map.Entry<IExpirable, Integer> entry : update.entrySet()) {
                timeByExpirable.put(entry.getKey(), entry.getValue());
            }

            if(intervalcount == saveinterval) {
                save();
                intervalcount = 0;
            } else {
                intervalcount += interval;
            }
        } finally {
            timerLock.unlock();
        }
    }

    private String removeTrailingCounter(String name) {
        if(name.contains("~"))
            return name.substring(0, name.lastIndexOf('~')).replace("=", ".");
        return name;
    }

    private void save() {
        HashMap<String, HashMap<String, String>> saveStructure = new HashMap<String, HashMap<String, String>>();
        Long counter = 0L;
        for(Map.Entry<IExpirable, Integer> entry : timeByExpirable.entrySet()) {
            HashMap<String, String> values = new HashMap<String, String>();
            values.put("_timeleft", entry.getValue().toString());
            values.putAll(entry.getKey().getEntry());
            saveStructure.put(entry.getKey().getName().replace(".", "=") + "~" + counter.toString(), values);
            counter++;
        }
        this.storageConfiguration.set("expirables", saveStructure);
        try {
            this.storageConfiguration.save(storageFile);
        } catch (IOException ex) {
            SignShop.log("Unable to save expirables to file due to: " + ex.getMessage(), Level.SEVERE);
        }
    }

    private Object tryReflection(String fullClassname) {
        try {
            Class<?> fc = (Class<?>)Class.forName(fullClassname);
            return fc.newInstance();
        } catch (InstantiationException ex) { }
        catch (IllegalAccessException ex) { }
        catch (ClassNotFoundException ex) { }

        return null;
    }

    private HashMap<String,HashMap<String,String>> fetchHasmapInHashmap(String path, FileConfiguration config) {
        HashMap<String,HashMap<String,String>> tempHasinHash = new HashMap<String,HashMap<String,String>>();
        try {
            if(config.getConfigurationSection(path) == null)
                return tempHasinHash;
            Map<String, Object> messages_section = config.getConfigurationSection(path).getValues(false);
            for(Map.Entry<String, Object> entry : messages_section.entrySet()) {
                MemorySection memsec = (MemorySection)entry.getValue();
                HashMap<String,String> tempmap = new HashMap<String, String>();
                for(Map.Entry<String, Object> subentry : memsec.getValues(false).entrySet())
                    tempmap.put(subentry.getKey(), (String)subentry.getValue());
                tempHasinHash.put(entry.getKey(), tempmap);
            }
        } catch(ClassCastException ex) { }

        return tempHasinHash;
    }

    private static Method fetchSchedulerMethod(String methodName) {
        try {
            return Bukkit.getScheduler().getClass().getDeclaredMethod(methodName, Plugin.class, Runnable.class, long.class, long.class);
        }
        catch (NoSuchMethodException ex) { }
        catch (SecurityException ex) { }

        return null;
    }

    private void scheduleCheck() {
        boolean ranOperation = false;
        Method scheduleAsync = fetchSchedulerMethod("runTaskTimerAsynchronously");
        String reason = "Method was not found";

        if(scheduleAsync == null)
            scheduleAsync = fetchSchedulerMethod("scheduleAsyncRepeatingTask");

        if(scheduleAsync != null) {
            try {
                scheduleAsync.invoke(Bukkit.getScheduler(), SignShop.getInstance(), this, 0, getTicks(interval));
                ranOperation = true;
            }
            catch (IllegalAccessException ex) { reason = ex.getMessage(); }
            catch (IllegalArgumentException ex) { reason = ex.getMessage(); }
            catch (InvocationTargetException ex) { reason = ex.getMessage(); }
        }

        if(!ranOperation)
            SignShop.log("Could not find proper method to schedule TimeManager task! Reason: " + reason, Level.SEVERE);
    }

    private int getTicks(int ms) {
        return (ms / 50);
    }

    private int getSeconds(int ms) {
        return (ms / 1000);
    }
}
