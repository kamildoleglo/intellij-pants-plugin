// Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.execution;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationModule;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathsList;
import com.intellij.util.Processor;
import com.twitter.intellij.pants.metrics.PantsExternalMetricsListenerManager;
import com.twitter.intellij.pants.model.TargetAddressInfo;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class PantsClasspathRunConfigurationExtension extends RunConfigurationExtension {
  protected static final Logger LOG = Logger.getInstance(PantsClasspathRunConfigurationExtension.class);
  private static final Gson gson = new Gson();

  /**
   * The goal of this function is to find classpath for IntelliJ JUnit/Scala runner.
   * <p/>
   * This function will fail if the projects' Pants doesn't support `--export-classpath-manifest-jar-only`.
   * It also assumes that Pants has created a manifest jar that contains all the classpath links for the
   * particular test that's being run.
   */
  @Override
  public <T extends RunConfigurationBase<?>> void updateJavaParameters(
    @NotNull T configuration,
    @NotNull JavaParameters params,
    RunnerSettings runnerSettings
  ) throws ExecutionException {
    List<BeforeRunTask<?>> tasks = ((RunManagerImpl) RunManager.getInstance(configuration.getProject())).getBeforeRunTasks(configuration);
    boolean builtByPants = tasks.stream().anyMatch(s -> s.getProviderId().equals(PantsMakeBeforeRun.ID));
    // Not built by Pants means it was built by IntelliJ, in which case we don't need to change the classpath.
    if (!builtByPants) {
      return;
    }

    final Module module = findPantsModule(configuration);
    if (module == null) {
      return;
    }

    Set<String> pathsAllowed = calculatePathsAllowed(params);

    final PathsList classpath = params.getClassPath();
    List<String> classpathToKeep = classpath.getPathList().stream()
      .filter(cp -> pathsAllowed.stream().anyMatch(cp::contains)).collect(Collectors.toList());
    classpath.clear();
    classpath.addAll(classpathToKeep);

    VirtualFile manifestJar = PantsUtil.findProjectManifestJar(configuration.getProject())
      .orElseThrow(() -> new ExecutionException("manifest.jar is not found. It should be generated by `./pants export-classpath ...`"));
    classpath.add(manifestJar.getPath());

    PantsExternalMetricsListenerManager.getInstance().logTestRunner(configuration);
  }

  /**
   * Filter out all jars imported to the project with the exception of
   * jars on the JDK path, IntelliJ installation paths, and plugin installation path,
   * because Pants is already exporting all the runtime classpath in manifest.jar.
   *
   * Exception applies during unit test of this plugin because "JUnit" and "com.intellij"
   * plugin lives under somewhere under ivy, whereas during normal usage, they are covered
   * by `homePath`.
   *
   *
   * @param params JavaParameters
   * @return a set of paths that should be allowed to passed to the test runner.
   */
  @NotNull
  private Set<String> calculatePathsAllowed(JavaParameters params) {

    String homePath = PathManager.getHomePath();
    String pluginPath = PathManager.getPluginsPath();

    Set<String> pathsAllowed = Sets.newHashSet(homePath, pluginPath);

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      // /Users/yic/.ivy2/pants/com.intellij.sdk.community/idea_rt/jars/idea_rt-latest.jar ->
      // /Users/yic/.ivy2/pants/com.intellij.sdk.community/
      Optional.ofNullable(PluginManagerCore.getPlugin(PluginId.getId("com.intellij")))
        .map(s -> s.getPath().getParentFile().getParentFile().getParentFile().getAbsolutePath())
        .ifPresent(pathsAllowed::add);

      // At this point we know junit plugin is already enabled.
      // //Users/yic/.ivy2/pants/com.intellij.junit-plugin/junit-rt/jars/junit-rt-latest.jar ->
      // /Users/yic/.ivy2/pants/com.intellij.junit-plugin/
      Optional.ofNullable(PluginManagerCore.getPlugin(PluginId.getId("JUnit")))
        .map(s -> s.getPath().getParentFile().getParentFile().getParentFile().getAbsolutePath())
        .ifPresent(pathsAllowed::add);
    }
    return pathsAllowed;
  }

  @NotNull
  public static List<String> findPublishedClasspath(@NotNull Module module) {
    final List<String> result = new ArrayList<>();
    // This is type for Gson to figure the data type to deserialize
    final Type type = new TypeToken<HashSet<TargetAddressInfo>>() {}.getType();
    Set<TargetAddressInfo> targetInfoSet = gson.fromJson(module.getOptionValue(PantsConstants.PANTS_TARGET_ADDRESS_INFOS_KEY), type);
    // The new way to find classpath by target id
    for (TargetAddressInfo ta : targetInfoSet) {
      result.addAll(findPublishedClasspathByTargetId(module, ta));
    }
    return result;
  }

  @NotNull
  private static List<String> findPublishedClasspathByTargetId(@NotNull Module module, @NotNull TargetAddressInfo targetAddressInfo) {
    final Optional<VirtualFile> classpath = PantsUtil.findDistExportClasspathDirectory(module.getProject());
    if (!classpath.isPresent()) {
      return Collections.emptyList();
    }
    // Handle classpath with target.id
    List<String> paths = new ArrayList<>();
    int count = 0;
    while (true) {
      VirtualFile classpathLinkFolder = classpath.get().findFileByRelativePath(targetAddressInfo.getId() + "-" + count);
      VirtualFile classpathLinkFile = classpath.get().findFileByRelativePath(targetAddressInfo.getId() + "-" + count + ".jar");
      if (classpathLinkFolder != null && classpathLinkFolder.isDirectory()) {
        paths.add(classpathLinkFolder.getPath());
        break;
      }
      else if (classpathLinkFile != null) {
        paths.add(classpathLinkFile.getPath());
        count++;
      }
      else {
        break;
      }
    }
    return paths;
  }

  @NotNull
  private Map<String, String> findExcludes(@NotNull Module module) {
    final Map<String, String> result = new HashMap<>();
    processRuntimeModules(
      module,
      new Processor<Module>() {
        @Override
        public boolean process(Module module) {
          final String targets = module.getOptionValue(PantsConstants.PANTS_TARGET_ADDRESSES_KEY);
          final String excludes = module.getOptionValue(PantsConstants.PANTS_LIBRARY_EXCLUDES_KEY);
          for (String exclude : PantsUtil.hydrateTargetAddresses(excludes)) {
            result.put(exclude, StringUtil.notNullize(targets, module.getName()));
          }
          return true;
        }
      }
    );
    return result;
  }

  private void processRuntimeModules(@NotNull Module module, Processor<Module> processor) {
    final OrderEnumerator runtimeEnumerator = OrderEnumerator.orderEntries(module).runtimeOnly().recursively();
    runtimeEnumerator.forEachModule(processor);
  }


  @Nullable
  private <T extends RunConfigurationBase> Module findPantsModule(T configuration) {
    if (!(configuration instanceof ModuleBasedConfiguration)) {
      return null;
    }
    final RunConfigurationModule runConfigurationModule = ((ModuleBasedConfiguration) configuration).getConfigurationModule();
    final Module module = runConfigurationModule.getModule();
    if (module == null || !PantsUtil.isPantsModule(module)) {
      return null;
    }
    return module;
  }

  @Override
  protected void readExternal(@NotNull RunConfigurationBase runConfiguration, @NotNull Element element) throws InvalidDataException {

  }

  @Override
  protected void writeExternal(@NotNull RunConfigurationBase runConfiguration, @NotNull Element element) throws WriteExternalException {

  }

  @Nullable
  @Override
  protected String getEditorTitle() {
    return PantsConstants.PANTS;
  }

  @Override
  public boolean isApplicableFor(@NotNull RunConfigurationBase configuration) {
    return findPantsModule(configuration) != null;
  }

}
