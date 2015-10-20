package org.hotswap.agent.plugin.geminiblueprint.scanner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.MergeableCommand;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.IOUtils;
import org.hotswap.agent.watch.WatchFileEvent;

/**
 * Do refresh Spring class (scanned by classpath scanner) based on URI or byte[] definition.
 * <p>
 * This commands merges events of watcher.event(CREATE) and transformer hotswap reload to a single refresh command.
 */
public class ClassPathBeanRefreshCommand extends MergeableCommand {
  private static AgentLogger LOGGER = AgentLogger.getLogger(ClassPathBeanRefreshCommand.class);

  ClassLoader appClassLoader;

  String basePackage;

  String className;

  // either event or classDefinition is set by constructor (watcher or transformer)
  WatchFileEvent event;
  byte[] classDefinition;

  Object scannerAgent;

  public ClassPathBeanRefreshCommand(ClassLoader appClassLoader, Object scannerAgent, String basePackage, String className, byte[] classDefinition) {
    this.appClassLoader = appClassLoader;
    this.basePackage = basePackage;
    this.className = className;
    this.classDefinition = classDefinition;
    this.scannerAgent = scannerAgent;
  }

  public ClassPathBeanRefreshCommand(ClassLoader appClassLoader, Object scannerAgent, String basePackage, WatchFileEvent event) {
    this.appClassLoader = appClassLoader;
    this.basePackage = basePackage;
    this.event = event;

    // strip from URI prefix up to basePackage and .class suffix.
    String path = event.getURI().getPath();
    path = path.substring(path.indexOf(basePackage.replace(".", "/")));
    path = path.substring(0, path.indexOf(".class"));
    this.className = path;
    this.scannerAgent = scannerAgent;
  }

  @Override
  public void executeCommand() {
    if (isDeleteEvent()) {
      LOGGER.trace("Skip Spring reload for delete event on class '{}'", className);
      return;
    }

    try {
      if (classDefinition == null) {
        try {
          this.classDefinition = IOUtils.toByteArray(event.getURI());
        }
        catch (IllegalArgumentException e) {
          LOGGER.debug("File {} not found on filesystem (deleted?). Unable to refresh associated Spring bean.", event.getURI());
          return;
        }
      }
      LOGGER.debug("Executing ClassPathBeanDefinitionScannerAgent.refreshClass('{}')", className);


      scannerAgent.getClass().getMethod("resolveAndDefineBeanDefinition", new Class[] {byte[].class}).invoke(scannerAgent, classDefinition);
//      scannerAgent.resolveAndDefineBeanDefinition(classDefinition);
//      Class<?> clazz = springBundleClassLoader.loadClass("org.hotswap.agent.plugin.geminiblueprint.scanner.GeminiClassPathBeanDefinitionScannerAgent");
//
//      Method method = clazz.getDeclaredMethod(
//          "refreshClass", new Class[] {String.class, byte[].class});
//      method.invoke(null, basePackage, classDefinition);
    }
    catch (NoSuchMethodException e) {
      throw new IllegalStateException("Plugin error, method not found", e);
    }
    catch (InvocationTargetException e) {
      LOGGER.error("Error refreshing class {} in classLoader {}", e, className, appClassLoader);
    }
    catch (IllegalAccessException e) {
      throw new IllegalStateException("Plugin error, illegal access", e);
    }
//    catch (ClassNotFoundException e) {
//      throw new IllegalStateException("Plugin error, Spring class not found in application classloader", e);
//    }
//    catch (IOException e) {
//      throw new IllegalStateException("Plugin error, IOException", e);
//    }

  }

  private Class<?> loadClass(String className) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, ClassNotFoundException {
    //if jboss
    if (appClassLoader.getClass().getName().equals("org.jboss.modules.ModuleClassLoader")) {
      Method method = ClassLoader.class.getDeclaredMethod("findClass", String.class);
      method.setAccessible(true);
      return (Class<?>) method.invoke(appClassLoader, "org.hotswap.agent.plugin.spring.scanner.ClassPathBeanDefinitionScannerAgent");
    }

    return appClassLoader.loadClass(className);
  }

  /**
   * Check all merged events for delete and create events. If delete without create is found, than assume
   * file was deleted.
   */
  private boolean isDeleteEvent() {
    // for all merged commands including this command
    List<ClassPathBeanRefreshCommand> mergedCommands = new ArrayList<ClassPathBeanRefreshCommand>();
    for (Command command : getMergedCommands()) {
      mergedCommands.add((ClassPathBeanRefreshCommand) command);
    }
    mergedCommands.add(this);

    boolean createFound = false;
    boolean deleteFound = false;
    for (ClassPathBeanRefreshCommand command : mergedCommands) {
      if (command.event != null) {
        if (command.event.getEventType().equals(FileEvent.DELETE))
          deleteFound = true;
        if (command.event.getEventType().equals(FileEvent.CREATE))
          createFound = true;
      }
    }

    LOGGER.trace("isDeleteEvent result {}: createFound={}, deleteFound={}", createFound, deleteFound);
    return !createFound && deleteFound;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ClassPathBeanRefreshCommand that = (ClassPathBeanRefreshCommand) o;

    if (!appClassLoader.equals(that.appClassLoader)) return false;
    if (!className.equals(that.className)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = appClassLoader.hashCode();
    result = 31 * result + className.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "ClassPathBeanRefreshCommand{" +
        "appClassLoader=" + appClassLoader +
        ", basePackage='" + basePackage + '\'' +
        ", className='" + className + '\'' +
        '}';
  }
}