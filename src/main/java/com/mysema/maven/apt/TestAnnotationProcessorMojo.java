/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.maven.apt;

import java.io.File;

/**
 * TestAnnotationProcessorMojo calls APT processors for code generation
 * 
 * @goal test-process
 * @phase generate-sources
 * @requiresDependencyResolution test
 */
public class TestAnnotationProcessorMojo extends AbstractProcessorMojo {

    /**
     * @parameter
     */
    protected File outputDirectory;

    /**
     * @parameter
     */
    protected File testOutputDirectory;

    /**
     * @parameter expression="${project.build.testSourceDirectory}" required=true
     */
    protected File sourceDirectory;
  
    @Override
    public File getOutputDirectory() {
        return testOutputDirectory != null ? testOutputDirectory : outputDirectory;
    }

    @Override
    protected File getSourceDirectory() {
        return sourceDirectory;
    }

    @Override
    protected boolean isForTest(){
        return true;
    }
    
}
