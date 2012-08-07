/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.maven.apt;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Base class for AnnotationProcessorMojo implementations
 * 
 * @author tiwe
 * 
 */
public abstract class AbstractProcessorMojo extends AbstractMojo {

    private static final String JAVA_FILE_FILTER = "/*.java";
    private static final String[] ALL_JAVA_FILES_FILTER = new String[] { "**" + JAVA_FILE_FILTER };

    /**
     * @component
     */
    protected BuildContext buildContext;

    /**
     * @parameter expression="${project}" readonly=true required=true
     */
    protected MavenProject project;

    /**
     * @parameter
     */
    protected String[] processors;

    /**
     * @parameter
     */
    protected String processor;

    /**
     * @parameter expression="${project.build.sourceEncoding}" required=true
     */
    protected String sourceEncoding;

    /**
     * @parameter
     */
    protected Map<String, String> options;

    /**
     * @parameter
     */
    protected Map<String, String> compilerOptions;

    /**
     * A list of inclusion package filters for the apt processor.
     * 
     * If not specified all sources will be used for apt processor
     * 
     * <pre>
     * e.g.:
     * &lt;includes&gt;
     * 	&lt;include&gt;com.mypackge.**.bo.**&lt;/include&gt;
     * &lt;/includes&gt;
     * </pre>
     * 
     * will include all files which match com/mypackge/ ** /bo/ ** / *.java
     * 
     * @parameter
     */
    protected Set<String> includes = new HashSet<String>();

    /**
     * @parameter
     */
    protected boolean showWarnings = false;

    /**
     * @parameter
     */
    protected boolean logOnlyOnError = false;

    /**
     * @parameter expression="${plugin.artifacts}" readonly=true required=true
     */
    private List<Artifact> pluginArtifacts;

    @SuppressWarnings("unchecked")
    private String buildCompileClasspath() {
        List<String> pathElements = null;
        try {
            if (isForTest()) {
                pathElements = project.getTestClasspathElements();
            } else {
                pathElements = project.getCompileClasspathElements();
            }
        } catch (DependencyResolutionRequiredException e) {
            super.getLog().warn("exception calling getCompileClasspathElements", e);
            return null;
        }

        if (pluginArtifacts != null) {
            for (Artifact a : pluginArtifacts) {
                if (a.getFile() != null) {
                    pathElements.add(a.getFile().getAbsolutePath());
                }
            }
        }

        if (pathElements.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        for (i = 0; i < pathElements.size() - 1; ++i) {
            result.append(pathElements.get(i)).append(File.pathSeparatorChar);
        }
        result.append(pathElements.get(i));
        return result.toString();
    }

    private String buildProcessor() {
        if (processors != null) {
            StringBuilder result = new StringBuilder();
            for (String processor : processors) {
                if (result.length() > 0) {
                    result.append(",");
                }
                result.append(processor);
            }
            return result.toString();
        } else if (processor != null) {
            return processor;
        } else {
            String error = "Either processor or processors need to be given";
            getLog().error(error);
            throw new IllegalArgumentException(error);
        }
    }

    private List<String> buildCompilerOptions(String processor, String compileClassPath) throws IOException {
        Map<String, String> compilerOpts = new LinkedHashMap<String, String>();

        // Default options
        compilerOpts.put("cp", compileClassPath);

        if (sourceEncoding != null) {
            compilerOpts.put("encoding", sourceEncoding);
        }

        compilerOpts.put("proc:only", null);
        compilerOpts.put("processor", processor);

        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                compilerOpts.put("A" + entry.getKey() + "=" + entry.getValue(), null);
            }
        }

        if (getOutputDirectory() != null) {
            compilerOpts.put("s", getOutputDirectory().getPath());
        }

        if (!showWarnings) {
            compilerOpts.put("nowarn", null);
        }

        compilerOpts.put("sourcepath", getSourceDirectory().getCanonicalPath());

        // User options override default options
        if (compilerOptions != null) {
            compilerOpts.putAll(compilerOptions);
        }

        List<String> opts = new ArrayList<String>(compilerOpts.size() * 2);

        for (Map.Entry<String, String> compilerOption : compilerOpts.entrySet()) {
            opts.add("-" + compilerOption.getKey());
            String value = compilerOption.getValue();
            if (StringUtils.isNotBlank(value)) {
                opts.add(value);
            }
        }
        return opts;
    }

    /**
     * Filter files for apt processing based on the {@link #includes} filter and
     * also taking into account m2e {@link BuildContext} to filter-out unchanged
     * files when invoked as incremental build
     * 
     * @param directory
     *            source directory in which files are located for apt processing
     * 
     * @return files for apt processing. Returns empty set when there is no
     *         files to process
     */
    private Set<File> filterFiles(File directory) {
        // support for incremental build in m2e context
        Scanner scanner = buildContext.newScanner(getSourceDirectory());
        scanner.setIncludes(ALL_JAVA_FILES_FILTER);

        // include based on the filter if specified
        if (includes != null && includes.isEmpty() == false) {
            String[] filters = includes.toArray(new String[includes.size()]);
            for (int i = 0; i < filters.length; i++) {
                filters[i] = filters[i].replace('.', '/') + JAVA_FILE_FILTER;
            }
            scanner.setIncludes(filters);
        }
        scanner.scan();

        String[] includedFiles = scanner.getIncludedFiles();
        if (includedFiles == null || includedFiles.length == 0) {
            // there is no relevant sources to generate classes from
            return Collections.emptySet();
        }

        Set<File> files = new HashSet<File>();
        for (String includedFile : includedFiles) {
            files.add(new File(scanner.getBasedir(), includedFile));
        }
        return files;
    }

    public void execute() throws MojoExecutionException {
        if (getOutputDirectory() != null && !getOutputDirectory().exists()) {
            getOutputDirectory().mkdirs();
        }

        getLog().debug("Using build context: " + buildContext);

        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new MojoExecutionException("You need to run build with JDK or have tools.jar on the classpath."
                        + "If this occures during eclipse build make sure you run eclipse under JDK as well");
            }

            Set<File> files = filterFiles(getSourceDirectory());
            if (files.isEmpty()) {
                getLog().debug("There is no sources to generatate querydsl classes from (skipping)");
                return;
            }

            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            Iterable<? extends JavaFileObject> compilationUnits1 = fileManager.getJavaFileObjectsFromFiles(files);

            String compileClassPath = buildCompileClasspath();

            String processor = buildProcessor();

            List<String> compilerOptions = buildCompilerOptions(processor, compileClassPath);

            Writer out = null;
            if (logOnlyOnError) {
                out = new StringWriter();
            }
            CompilationTask task = compiler.getTask(out, fileManager, null, compilerOptions, null, compilationUnits1);
            // Perform the compilation task.
            Boolean rv = task.call();
            if (Boolean.FALSE.equals(rv) && logOnlyOnError) {
                getLog().error(out.toString());
            }

            if (getOutputDirectory() != null) {
                if (isForTest()) {
                    project.addTestCompileSourceRoot(getOutputDirectory().getAbsolutePath());
                } else {
                    project.addCompileSourceRoot(getOutputDirectory().getAbsolutePath());
                }

                buildContext.refresh(getOutputDirectory());
            }

        } catch (Exception e1) {
            super.getLog().error("execute error", e1);
            throw new MojoExecutionException(e1.getMessage());
        }
    }

    protected abstract File getSourceDirectory();

    protected abstract File getOutputDirectory();

    protected boolean isForTest() {
        return false;
    }

}
