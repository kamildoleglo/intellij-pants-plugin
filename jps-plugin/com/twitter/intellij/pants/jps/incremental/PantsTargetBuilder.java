// Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.jps.incremental;

import com.google.gson.Gson;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import com.twitter.intellij.pants.jps.incremental.model.JpsPantsProjectExtension;
import com.twitter.intellij.pants.jps.incremental.model.PantsBuildTarget;
import com.twitter.intellij.pants.jps.incremental.model.PantsBuildTargetType;
import com.twitter.intellij.pants.jps.incremental.model.PantsSourceRootDescriptor;
import com.twitter.intellij.pants.jps.incremental.serialization.PantsJpsProjectExtensionSerializer;
import com.twitter.intellij.pants.jps.util.PantsJpsUtil;
import com.twitter.intellij.pants.service.project.model.TargetAddressInfo;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsOutputMessage;
import com.twitter.intellij.pants.util.PantsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.FileProcessor;
import org.jetbrains.jps.builders.java.JavaBuilderUtil;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.TargetBuilder;
import org.jetbrains.jps.incremental.java.JavaBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.model.JpsProject;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PantsTargetBuilder extends TargetBuilder<PantsSourceRootDescriptor, PantsBuildTarget> {
  private static final Logger LOG = Logger.getInstance(PantsTargetBuilder.class);

  public PantsTargetBuilder() {
    super(Collections.singletonList(PantsBuildTargetType.INSTANCE));
  }

  private class SimpleExportResult {
    public String version;
  }

  @NotNull
  @Override
  public String getPresentableName() {
    return "Pants Compiler";
  }

  @Override
  public void buildStarted(CompileContext context) {
    super.buildStarted(context);
    final JpsProject jpsProject = context.getProjectDescriptor().getProject();
    final JpsPantsProjectExtension pantsProjectExtension = PantsJpsProjectExtensionSerializer.findPantsProjectExtension(jpsProject);
    final boolean compileWithPants = pantsProjectExtension != null && !pantsProjectExtension.isCompileWithIntellij();
    if (compileWithPants && PantsJpsUtil.containsPantsModules(jpsProject.getModules())) {
      // disable only for imported projects
      JavaBuilder.IS_ENABLED.set(context, Boolean.FALSE);
    }
  }

  @Override
  public void build(
    @NotNull PantsBuildTarget target,
    @NotNull DirtyFilesHolder<PantsSourceRootDescriptor, PantsBuildTarget> holder,
    @NotNull BuildOutputConsumer outputConsumer,
    @NotNull final CompileContext context
  ) throws ProjectBuildException, IOException {
    if (!hasDirtyTargets(holder) && !JavaBuilderUtil.isForcedRecompilationAllJavaModules(context)) {
      context.processMessage(new CompilerMessage(PantsConstants.PANTS, BuildMessage.Kind.INFO, "No changes to compile."));
      return;
    }

    ProcessOutput output;
    if (supportsExportClasspath(target.getPantsExecutable())) {
      output = runCompile(target, holder, context, "export-classpath", "compile");
    }
    else {
      output = runCompile(target, holder, context, "compile");
    }

    boolean success = output.checkSuccess(LOG);
    if (!success) {
      throw new ProjectBuildException(output.getStderr());
    }
  }

  private boolean supportsExportClasspath(@NotNull String pantsExecutable) {
    final GeneralCommandLine commandLine = PantsUtil.defaultCommandLine(new File(pantsExecutable));
    commandLine.addParameter("goals");
    try {
      return PantsUtil.getCmdOutput(commandLine, null).getStdout().contains("export-classpath");
    }
    catch (ExecutionException e) {
      LOG.warn(e);
      return false;
    }
  }

  private ProcessOutput runCompile(
    @NotNull PantsBuildTarget target,
    @NotNull DirtyFilesHolder<PantsSourceRootDescriptor, PantsBuildTarget> holder,
    @NotNull final CompileContext context,
    String... goals
  ) throws IOException, ProjectBuildException {
    final String pantsExecutable = target.getPantsExecutable();
    final GeneralCommandLine commandLine = PantsUtil.defaultCommandLine(pantsExecutable);
    final Set<String> allNonGenTargets = filterGenTargets(target.getTargetAddresses());
    final Set<TargetAddressInfo> x = target.getTargetAddressInfoSet();

    if (JavaBuilderUtil.isForcedRecompilationAllJavaModules(context)) {
      final String recompileMessage = String.format("Recompiling all %s targets", allNonGenTargets.size());
      context.processMessage(
        new CompilerMessage(PantsConstants.PANTS, BuildMessage.Kind.INFO, recompileMessage)
      );
      context.processMessage(new ProgressMessage(recompileMessage));
      commandLine.addParameters("clean-all");
      commandLine.addParameters(goals);
      for (String targetAddress : allNonGenTargets) {
        commandLine.addParameter(targetAddress);
      }
    }
    else {
      // Pants does compile incrementally and it appeared calling Pants for only findTargetAddresses(holder) targets
      // isn't very beneficial. To simplify the plugin we are going to rely on Pants and pass all targets in the project.
      // We can't use project settings because a project can be generated from a script file or is opened with dependeees.
      final Set<String> changedNonGenTargets = filterGenTargets(findTargetAddresses(holder));
      String recompileMessage;
      if (changedNonGenTargets.size() == 1) {
        recompileMessage = String.format("Recompiling %s", changedNonGenTargets.iterator().next());
      }
      else {
        recompileMessage = String.format("Recompiling %s targets", changedNonGenTargets.size());
      }
      context.processMessage(
        new CompilerMessage(PantsConstants.PANTS, BuildMessage.Kind.INFO, recompileMessage
        ));
      context.processMessage(new ProgressMessage(recompileMessage));
      commandLine.addParameters(goals);
      for (String targetAddress : changedNonGenTargets) {
        commandLine.addParameter(targetAddress);
      }
    }

    // Find out whether "export-classpath-use-old-naming-style" is supported
    final GeneralCommandLine optionCommandLine = PantsUtil.defaultCommandLine(pantsExecutable);
    optionCommandLine.addParameters("options", "--no-colors");
    final boolean hasExportClassPathNamingStyle;
    try {
      final ProcessOutput processOutput = PantsUtil.getProcessOutput(optionCommandLine, null);
      final String stdout = processOutput.getStdout();
      hasExportClassPathNamingStyle = StringUtil.contains(stdout, PantsConstants.PANTS_EXPORT_CLASSPATH_USE_TARGET_ID);
    }
    catch (ExecutionException e) {
      throw new ProjectBuildException("./pants options failed");
    }

    final boolean exportContainsTargetId;
    final GeneralCommandLine exportCommandline = PantsUtil.defaultCommandLine(pantsExecutable);
    exportCommandline.addParameters("export", "--no-colors");
    try {
      final ProcessOutput processOutput = PantsUtil.getProcessOutput(exportCommandline, null);
      final String stdOut = processOutput.getStdout();
      SimpleExportResult simpleExportResult = (new Gson()).fromJson(stdOut, SimpleExportResult.class);
      exportContainsTargetId = versionCompare(simpleExportResult.version, "1.0.5") >= 0;
    }
    catch (ExecutionException e) {
      throw new ProjectBuildException("./pants options failed");
    }

    commandLine.addParameters("--no-colors");
    if (hasExportClassPathNamingStyle && exportContainsTargetId) {
      commandLine.addParameters("--no-export-classpath-use-old-naming-style");
    }

    final Process process;
    try {
      process = commandLine.createProcess();
    }
    catch (ExecutionException e) {
      throw new ProjectBuildException(e);
    }
    final CapturingProcessHandler processHandler = new CapturingAnsiEscapesAwareProcessHandler(process);
    processHandler.addProcessListener(
      new ProcessAdapter() {
        @Override
        public void onTextAvailable(ProcessEvent event, Key outputType) {
          super.onTextAvailable(event, outputType);
          context.processMessage(getCompilerMessage(event, outputType));
        }
      }
    );
    return processHandler.runProcess();
  }

  private boolean hasDirtyTargets(DirtyFilesHolder<PantsSourceRootDescriptor, PantsBuildTarget> holder) throws IOException {
    final Ref<Boolean> hasDirtyTargets = Ref.create(false);
    holder.processDirtyFiles(
      new FileProcessor<PantsSourceRootDescriptor, PantsBuildTarget>() {
        @Override
        public boolean apply(PantsBuildTarget target, File file, PantsSourceRootDescriptor root) throws IOException {
          if (!PantsJpsUtil.containsGenTarget(root.getTargetAddresses())) {
            hasDirtyTargets.set(true);
            return false;
          }
          return true;
        }
      }
    );
    return hasDirtyTargets.get();
  }

  private Set<String> filterGenTargets(@NotNull Collection<String> addresses) {
    return new HashSet<String>(
      ContainerUtil.filter(
        addresses,
        new Condition<String>() {
          @Override
          public boolean value(String targetAddress) {
            return !PantsJpsUtil.isGenTarget(targetAddress);
          }
        }
      )
    );
  }

  @NotNull
  private Set<String> findTargetAddresses(@NotNull DirtyFilesHolder<PantsSourceRootDescriptor, PantsBuildTarget> holder)
    throws IOException {
    final Set<String> addresses = new HashSet<String>();
    holder.processDirtyFiles(
      new FileProcessor<PantsSourceRootDescriptor, PantsBuildTarget>() {
        @Override
        public boolean apply(PantsBuildTarget target, File file, PantsSourceRootDescriptor root) throws IOException {
          addresses.addAll(root.getTargetAddresses());
          return true;
        }
      }
    );
    return addresses;
  }

  @NotNull
  public CompilerMessage getCompilerMessage(ProcessEvent event, Key<?> outputType) {
    final PantsOutputMessage message = PantsOutputMessage.parseCompilerMessage(event.getText());
    if (message == null) {
      final String outputMessage = StringUtil.trim(event.getText());
      final boolean isError = PantsOutputMessage.isError(outputMessage) || StringUtil.startsWith(outputMessage, "FAILURE");
      final boolean isWarning = PantsOutputMessage.isWarning(outputMessage);
      return new CompilerMessage(
        PantsConstants.PANTS,
        isError ? BuildMessage.Kind.ERROR : isWarning ? BuildMessage.Kind.WARNING : BuildMessage.Kind.INFO,
        outputMessage
      );
    }

    final boolean isError = outputType == ProcessOutputTypes.STDERR || message.getLevel() == PantsOutputMessage.Level.ERROR;
    final boolean isWarning = message.getLevel() == PantsOutputMessage.Level.WARNING;
    final BuildMessage.Kind kind =
      isError ? BuildMessage.Kind.ERROR : isWarning ? BuildMessage.Kind.WARNING : BuildMessage.Kind.INFO;
    return new CompilerMessage(
      PantsConstants.PANTS,
      kind,
      event.getText().substring(message.getEnd()),
      message.getFilePath(),
      -1L, -1L, -1L, message.getLineNumber() + 1, -1L
    );
  }

  /**
   * Compares two version strings.
   * <p/>
   * Use this instead of String.compareTo() for a non-lexicographical
   * comparison that works for version strings. e.g. "1.10".compareTo("1.6").
   *
   * @param str1 a string of ordinal numbers separated by decimal points.
   * @param str2 a string of ordinal numbers separated by decimal points.
   * @return The result is a negative integer if str1 is _numerically_ less than str2.
   * The result is a positive integer if str1 is _numerically_ greater than str2.
   * The result is zero if the strings are _numerically_ equal.
   * @note It does not work if "1.10" is supposed to be equal to "1.10.0".
   */
  public Integer versionCompare(String str1, String str2) {
    String[] vals1 = str1.split("\\.");
    String[] vals2 = str2.split("\\.");
    int i = 0;
    // set index to first non-equal ordinal or length of shortest version string
    while (i < vals1.length && i < vals2.length && vals1[i].equals(vals2[i])) {
      i++;
    }
    // compare first non-equal ordinal number
    if (i < vals1.length && i < vals2.length) {
      int diff = Integer.valueOf(vals1[i]).compareTo(Integer.valueOf(vals2[i]));
      return Integer.signum(diff);
    }
    // the strings are equal or one string is a substring of the other
    // e.g. "1.2.3" = "1.2.3" or "1.2.3" < "1.2.3.4"
    else {
      return Integer.signum(vals1.length - vals2.length);
    }
  }
}
