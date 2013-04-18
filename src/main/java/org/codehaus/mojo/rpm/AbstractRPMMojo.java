package org.codehaus.mojo.rpm;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.codehaus.mojo.rpm.VersionHelper.RPMVersionableMojo;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;

/**
 * Abstract base class for building RPMs.
 * 
 * @author Carlos
 * @author Brett Okken, Cerner Corp.
 * @version $Id$
 */
abstract class AbstractRPMMojo
    extends AbstractMojo
    implements RPMVersionableMojo
{
    /**
     * Maintains a mapping of macro keys to their values (either {@link RPMHelper#evaluateMacro(String) evaluated} or
     * set via {@link #defineStatements}.
     * 
     * @since 2.1-alpha-1
     */
    private final Map<String, String> macroKeyToValue = new HashMap<String, String>();

    /**
     * The key of the map is the directory where the files should be linked to. The value is the {@code List} of
     * {@link SoftlinkSource}s to be linked to.
     * 
     * @since 2.0-beta-3
     */
    private final Map<String, List<SoftlinkSource>> linkTargetToSources = new LinkedHashMap<String, List<SoftlinkSource>>();

    /**
     * The name portion of the output file name.
     */
    @Parameter( required = true, property = "project.artifactId")
    private String name;

    /**
     * The version portion of the RPM file name.
     */
    @Parameter( required = true, alias = "version", property = "project.version" )
    private String projversion;

    /**
     * The release portion of the RPM file name.
     * <p>
     * Beginning with 2.0-beta-2, this is an optional parameter. By default, the release will be generated from the
     * modifier portion of the <a href="#projversion">project version</a> using the following rules:
     * <ul>
     * <li>If no modifier exists, the release will be <code>1</code>.</li>
     * <li>If the modifier ends with <i>SNAPSHOT</i>, the timestamp (in UTC) of the build will be appended to end.</li>
     * <li>All instances of <code>'-'</code> in the modifier will be replaced with <code>'_'</code>.</li>
     * <li>If a modifier exists and does not end with <i>SNAPSHOT</i>, <code>"_1"</code> will be appended to end.</li>
     * </ul>
     * </p>
     */
    @Parameter
    private String release;

    /**
     * The target architecture for the rpm. The default value is <i>noarch</i>.
     * <p>
     * For passivity purposes, a value of <code>true</code> or <code>false</code> will indicate whether the <a
     * href="http://plexus.codehaus.org/plexus-utils/apidocs/org/codehaus/plexus/util/Os.html#OS_ARCH">architecture</a>
     * of the build machine will be used. Any other value (such as <tt>x86_64</tt>) will set the architecture of the rpm
     * to <tt>x86_64</tt>.
     * </p>
     * <p>
     * This can also be used in conjunction with <a href="source-params.html#targetArchitecture">Source
     * targetArchitecture</a> to flex the contents of the rpm based on the architecture.
     * </p>
     */
    @Parameter
    private String needarch;

    /**
     * The actual targeted architecture. This will be based on evaluation of {@link #needarch}.
     */
    private String targetArch;

    /**
     * The target os for building the RPM. By default, this will be populated to <a
     * href="http://plexus.codehaus.org/plexus-utils/apidocs/org/codehaus/plexus/util/Os.html#OS_NAME">Os.OS_NAME</a>.
     * <p>
     * This can be used in conjunction with <a href="source-params.html#targetOSName">Source targetOSName</a> to flex
     * the contents of the rpm based on operating system.
     * </p>
     *
     * @since 2.0-beta-3
     */
    @Parameter
    private String targetOS;

    /**
     * The target vendor for building the RPM. By default, this will be populated to the result of <i>rpm -E
     * %{_host_vendor}</i>.
     * 
     * @since 2.0-beta-3
     */
    @Parameter
    private String targetVendor;

    /**
     * Set to a key name to sign the package using GPG. If <i>keyPassphrase</i> is not also provided, this will require
     * the input of the passphrase at the terminal.
     */
    @Parameter( property = "gpg.keyname" )
    private String keyname;

    /**
     * The passphrase for the <i>keyname</i> to sign the rpm. This utilizes <a href="http://expect.nist.gov/">expect</a>
     * and requires that {@code expect} be on the PATH.
     * <p>
     * Note that the data type used is <b>NOT</b> {@code String}.
     * 
     * <pre>
     * &lt;configuration&gt;
     *     ...
     *     &lt;keyPassphrase&gt;
     *         &lt;passphrase&gt;<i>password</i>&lt;/passphrase&gt;
     *     &lt;/keyPassphrase&gt;
     * </pre>
     * 
     * </p>
     * 
     * @since 2.0-beta-4
     */
    @Parameter
    private Passphrase keyPassphrase;

    /**
     * The long description of the package.
     */
    @Parameter( property = "project.description" )
    private String description;

    /**
     * The one-line description of the package.
     */
    @Parameter( property = "project.name" )
    private String summary;

    /**
     * The one-line copyright information.
     */
    @Parameter
    private String copyright;

    /**
     * The distribution containing this package.
     */
    @Parameter
    private String distribution;

    /**
     * An icon for the package.
     */
    @Parameter
    private File icon;

    /**
     * The vendor supplying the package.
     */
    @Parameter( property = "project.organization.name" )
    private String vendor;

    /**
     * A URL for the vendor.
     */
    @Parameter( property = "project.organization.url" )
    private String url;

    /**
     * The package group for the package.
     */
    @Parameter( required = true )
    private String group;

    /**
     * The name of the person or group creating the package.
     */
    @Parameter( property = "project.organization.name" )
    private String packager;

    /**
     * Automatically add provided shared libraries.
     *
     * @since 2.0-beta-4
     */
    @Parameter( defaultValue = "true" )
    private boolean autoProvides;

    /**
     * Automatically add requirements deduced from included shared libraries.
     * 
     * @since 2.0-beta-4
     */
    @Parameter( defaultValue = "true" )
    private boolean autoRequires;

    /**
     * The list of virtual packages provided by this package.
     */
    @Parameter
    private LinkedHashSet<String> provides;

    /**
     * The list of requirements for this package.
     */
    @Parameter
    private LinkedHashSet<String> requires;

    /**
     * The list of prerequisites for this package.
     * 
     * @since 2.0-beta-3
     */
    @Parameter
    private LinkedHashSet<String> prereqs;

    /**
     * The list of obsoletes for this package.
     * 
     * @since 2.0-beta-3
     */
    @Parameter
    private LinkedHashSet<String> obsoletes;

    /**
     * The list of conflicts for this package.
     */
    @Parameter
    private LinkedHashSet<String> conflicts;

    /**
     * The relocation prefix for this package.
     */
    @Parameter
    private String prefix;

    /**
     * The area for RPM to use for building the package.<br/>
     * <b>NOTE:</b> The absolute path to the workarea <i>MUST NOT</i> have a space in any of the directory names.
     * <p>
     * Beginning with release 2.0-beta-3, sub-directories will be created within the workarea for each execution of the
     * plugin within a life cycle.<br/>
     * The pattern will be <code>workarea/<i>name[-classifier]</i></code>.<br/>
     * The classifier portion is only applicable for the <a href="attached-rpm-mojo.html">attached-rpm</a> goal.
     * </p>
     */
    @Parameter( defaultValue = "${project.build.directory}/rpm" )
    private File workarea;

    /**
     * The list of file <a href="map-params.html">mappings</a>.
     */
    @Parameter( required = true )
    private List<Mapping> mappings;

    /**
     * The prepare script.
     *
     * @deprecated Use prepareScriplet
     */
    @Parameter
    private String prepare;

    /**
     * The location of the prepare script. A File which does not exist is ignored.
     * 
     * @deprecated Use prepareScriplet
     */
    @Parameter
    private File prepareScript;

    /**
     * The prepare scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet prepareScriptlet;

    /**
     * The pre-installation script.
     *
     * @deprecated Use preinstallScriplet
     */
    @Parameter
    private String preinstall;

    /**
     * The location of the pre-installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use preinstallScriplet
     */
    @Parameter
    private File preinstallScript;

    /**
     * The pre-installation scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet preinstallScriptlet;

    /**
     * The post-installation script.
     *
     * @deprecated Use postinstallScriplet
     */
    @Parameter
    private String postinstall;

    /**
     * The location of the post-installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * 
     * @deprecated Use postinstallScriplet
     */
    @Parameter
    private File postinstallScript;

    /**
     * The post install scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet postinstallScriptlet;

    /**
     * The installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use installScriplet
     */
    @Parameter
    private String install;

    /**
     * The location of the installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use installScriplet
     */
    @Parameter
    private File installScript;

    /**
     * The installation scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet installScriptlet;

    /**
     * The pre-removal script.
     *
     * @deprecated Use preremoveScriplet
     */
    @Parameter
    private String preremove;

    /**
     * The location of the pre-removal script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use preremoveScriplet
     */
    @Parameter
    private File preremoveScript;

    /**
     * The pre-removal scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet preremoveScriptlet;

    /**
     * The post-removal script.
     *
     * @deprecated Use postremoveScriplet
     */
    @Parameter
    private String postremove;

    /**
     * The location of the post-removal script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use postremoveScriplet
     */
    @Parameter
    private File postremoveScript;

    /**
     * The post-removal scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet postremoveScriptlet;

    /**
     * The verification script.
     *
     * @deprecated Use verifyScriplet
     */
    @Parameter
    private String verify;

    /**
     * The location of the verification script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use verifyScriplet
     */
    @Parameter
    private File verifyScript;

    /**
     * The verify scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet verifyScriptlet;

    /**
     * The clean script.
     *
     * @deprecated Use cleanScriplet
     */
    @Parameter
    private String clean;

    /**
     * The location of the clean script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     *
     * @deprecated Use cleanScriplet
     */
    @Parameter
    private File cleanScript;

    /**
     * The clean scriptlet.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet cleanScriptlet;

    /**
     * The pretrans scriptlet.
     * 
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet pretransScriptlet;

    /**
     * The posttrans script.
     *
     * @since 2.0-beta-4
     */
    @Parameter
    private Scriptlet posttransScriptlet;

    /**
     * The list of triggers to take place on installation of other packages.
     * 
     * <pre>
     *  &lt;triggers>
     *      &lt;installTrigger>
     *          &lt;subpackage>optional&lt;/subpackage>
     *          &lt;program>program to execute (if not shell) optional&lt;/program>
     *          &lt;script>actual contents of script - optional&lt;/script>
     *          &lt;scriptFile>location of file containing script - optional&lt;/script>
     *          &lt;fileEncoding>character encoding for script file - recommended&lt;/fileEncoding>
     *          &lt;triggers>
     *              &lt;trigger>package/version to trigger on (i.e. jre > 1.5)&lt;/trigger>
     *              ...
     *          &lt;/triggers>
     *      &lt;/installTrigger>
     *      &lt;removeTrigger>
     *          ...
     *      &lt;/removeTrigger>
     *      &lt;postRemoveTrigger>
     *          ...
     *      &lt;/postRemoveTrigger>
     *      ...
     *  &lt;/triggers>
     * </pre>
     *
     * @since 2.0-beta-4
     * @see BaseTrigger
     */
    @Parameter
    private List<BaseTrigger> triggers;

    /**
     * Filters (property files) to include during the interpolation of the pom.xml.
     *
     * @since 2.0
     */
    @Parameter
    private List<String> filters;

    /**
     * Expression preceded with the String won't be interpolated \${foo} will be replaced with ${foo}
     *
     * @since 2.0
     */
    @Parameter( property = "maven.rpm.escapeString" )
    private String escapeString;

    /**
     * @since 2.0
     */
    @Component
    private MavenSession session;

    /**
     * @since 2.0
     */
    @Component( role = org.apache.maven.shared.filtering.MavenFileFilter.class, hint = "default" )
    private MavenFileFilter mavenFileFilter;

    /**
     * The {@link FileUtils.FilterWrapper filter wrappers} to use for file filtering.
     * 
     * @since 2.0
     * @see #mavenFileFilter
     */
    private List<FileUtils.FilterWrapper> defaultFilterWrappers;

    /**
     * The primary project artifact.
     */
    @Parameter( required = true, readonly = true, property = "project.artifact" )
    private Artifact artifact;

    /**
     * Auxillary project artifacts.
     */
    @Parameter( required = true, readonly = true, property = "project.attachedArtifacts" )
    private List<Artifact> attachedArtifacts;

    @Component
    protected MavenProject project;

    /**
     * A list of %define arguments
     */
    @Parameter
    private List<String> defineStatements;

    /**
     * The default file mode (octal string) to assign to files when installed. <br/>
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>, <a
     * href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> are
     * <b>NOT</b> populated.
     *
     * @since 2.0-beta-2
     */
    @Parameter
    private String defaultFilemode;

    /**
     * The default directory mode (octal string) to assign to directories when installed.<br/>
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>, <a
     * href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> are
     * <b>NOT</b> populated.
     *
     * @since 2.0-beta-2
     */
    @Parameter
    private String defaultDirmode;

    /**
     * The default user name for files when installed.<br/>
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>, <a
     * href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> are
     * <b>NOT</b> populated.
     *
     * @since 2.0-beta-2
     */
    @Parameter
    private String defaultUsername;

    /**
     * The default group name for files when installed.<br/>
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>, <a
     * href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> are
     * <b>NOT</b> populated.
     *
     * @since 2.0-beta-2
     */
    @Parameter
    private String defaultGroupname;

    /**
     * Indicates if the execution should be disabled. If <code>true</code>, nothing will occur during execution.
     *
     * @since 2.0
     */
    @Parameter
    private boolean disabled;

    /** The root of the build area prior to calling rpmbuild. */
    private File buildroot;

    /** The root of the build area as used by rpmbuild. */
    private File rpmBuildroot;

    /** The changelog string. */
    private String changelog;

    /**
     * The changelog file. If the file does not exist, it is ignored.
     *
     * @since 2.0-beta-3
     */
    @Parameter
    private File changelogFile;

    /**
     * This is not set until {@link #execute() is called}.
     * 
     * @since 2.1-alpha-1
     */
    private RPMHelper helper;

    /**
     * The system property to read the calculated version from, normally set by the version mojo.
     *
     * @since 2.1-alpha-2
     */
    @Parameter( required = true, defaultValue = "rpm.version" )
    private String versionProperty;

    /**
     * The system property to read the calculated release from, normally set by the version mojo.
     *
     * @since 2.1-alpha-2
     */
    @Parameter( required = true, defaultValue = "rpm.release" )
    private String releaseProperty;

    // // // Mojo methods

    /** {@inheritDoc} */
    public final void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( disabled )
        {
            getLog().info( "MOJO is disabled. Doing nothing." );
            return;
        }

        helper = new RPMHelper( this );

        checkParams( helper );

        final String classifier = getClassifier();

        if ( classifier != null )
        {
            workarea = new File( workarea, name + '-' + classifier );
        }
        else
        {
            workarea = new File( workarea, name );
        }

        buildWorkArea();

        // set up the maven file filter and FilteringDirectoryArchiver
        setDefaultWrappers();
        final FilteringDirectoryArchiver copier = new FilteringDirectoryArchiver();
        copier.setMavenFileFilter( mavenFileFilter );
        new FileHelper( this, copier ).installFiles();

        writeSpecFile();
        helper.buildPackage();

        afterExecution();
    }

    /**
     * Will be called on completion of {@link #execute()}. Provides subclasses an opportunity to perform any post
     * execution logic (such as attaching an artifact).
     * 
     * @throws MojoExecutionException If an error occurs.
     * @throws MojoFailureException If failure occurs.
     */
    protected void afterExecution()
        throws MojoExecutionException, MojoFailureException
    {

    }

    /**
     * Provides an opportunity for subclasses to provide an additional classifier for the rpm workarea.<br/>
     * By default this implementation returns {@code null}, which indicates that no additional classifier should be
     * used.
     * 
     * @return An additional classifier to use for the rpm workarea or {@code null} if no additional classifier should
     *         be used.
     */
    String getClassifier()
    {
        return null;
    }

    /**
     * Returns the generated rpm {@link File}.
     * 
     * @return The generated rpm <tt>File</tt>.
     */
    protected File getRPMFile()
    {
        File rpms = new File( workarea, "RPMS" );
        File archDir = new File( rpms, targetArch );

        return new File( archDir, name + '-' + projversion + '-' + release + '.' + targetArch + ".rpm" );
    }

    /**
     * @throws MojoExecutionException
     */
    private void setDefaultWrappers()
        throws MojoExecutionException
    {
        final MavenResourcesExecution mavenResourcesExecution = new MavenResourcesExecution();
        mavenResourcesExecution.setEscapeString( escapeString );

        try
        {
            defaultFilterWrappers =
                mavenFileFilter.getDefaultFilterWrappers( project, filters, false, this.session,
                                                          mavenResourcesExecution );
        }
        catch ( MavenFilteringException e )
        {
            getLog().error( "fail to build filering wrappers " + e.getMessage() );
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    /**
     * Build the structure of the work area.
     * 
     * @throws MojoFailureException if a directory cannot be built
     * @throws MojoExecutionException if buildroot cannot be cleared (if exists)
     */
    private void buildWorkArea()
        throws MojoFailureException, MojoExecutionException
    {
        final String[] topdirs = { "BUILD", "RPMS", "SOURCES", "SPECS", "SRPMS", "tmp-buildroot", "buildroot" };

        // Build the top directory
        if ( !workarea.exists() )
        {
            getLog().info( "Creating directory " + workarea.getAbsolutePath() );
            if ( !workarea.mkdirs() )
            {
                throw new MojoFailureException( "Unable to create directory " + workarea.getAbsolutePath() );
            }
        }

        validateWorkarea();

        // Build each directory in the top directory
        for ( int i = 0; i < topdirs.length; i++ )
        {
            File d = new File( workarea, topdirs[i] );
            if ( d.exists() )
            {
                getLog().info( "Directory " + d.getAbsolutePath() + " already exists. Deleting all contents." );

                try
                {
                    FileUtils.cleanDirectory( d );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Unable to clear directory: " + d.getName(), e );
                }
            }
            else
            {
                getLog().info( "Creating directory " + d.getAbsolutePath() );
                if ( !d.mkdir() )
                {
                    throw new MojoFailureException( "Unable to create directory " + d.getAbsolutePath() );
                }
            }
        }

        // set build root variable
        buildroot = new File( workarea, "tmp-buildroot" );
        rpmBuildroot = new File( workarea, "buildroot" );
    }

    /**
     * Check the parameters for validity.
     * 
     * @throws MojoFailureException if an invalid parameter is found
     * @throws MojoExecutionException if an error occurs reading a script
     */
    private void checkParams( RPMHelper helper )
        throws MojoExecutionException, MojoFailureException
    {
        Log log = getLog();

        // Retrieve any versions set by the VersionMojo
        String projversion = this.project.getProperties().getProperty( versionProperty );
        if ( projversion != null )
        {
            this.projversion = projversion;
        }
        String release = this.project.getProperties().getProperty( releaseProperty );
        if ( release != null )
        {
            this.release = release;
        }

        // If not set, calculate versions
        if ( this.projversion == null || this.release == null )
        {
            final VersionHelper.Version version = new VersionHelper( this ).calculateVersion();
            this.projversion = version.version;
            this.release = version.release;
        }

        log.debug( "project version = " + this.projversion );
        log.debug( "project release = " + this.release );

        // evaluate needarch and populate targetArch
        if ( needarch == null || needarch.length() == 0 || "false".equalsIgnoreCase( needarch ) )
        {
            targetArch = "noarch";
        }
        else if ( "true".equalsIgnoreCase( needarch ) )
        {
            targetArch = Os.OS_ARCH;
        }
        else
        {
            targetArch = needarch;
        }
        log.debug( "targetArch = " + targetArch );

        // provide default targetOS if value not given
        if ( targetOS == null || targetOS.length() == 0 )
        {
            targetOS = Os.OS_NAME;
        }
        log.debug( "targetOS = " + targetOS );

        if ( targetVendor == null || targetVendor.length() == 0 )
        {
            targetVendor = helper.getHostVendor();
        }
        log.debug( "targetVendor = " + targetVendor );

        // Various checks in the mappings
        for ( Mapping map : mappings )
        {
            if ( map.getDirectory() == null )
            {
                throw new MojoFailureException( "<mapping> element must contain the destination directory" );
            }
            if ( map.getSources() != null )
            {
                for ( Source src : map.getSources() )
                {
                    if ( src.getLocation() == null )
                    {
                        throw new MojoFailureException( "<mapping><source> tag must contain the source directory" );
                    }
                }
            }
        }

        prepareScriptlet = passiveScripts( "prepare", prepareScriptlet, prepare, prepareScript );
        preinstallScriptlet = passiveScripts( "preinstall", preinstallScriptlet, preinstall, preinstallScript );
        installScriptlet = passiveScripts( "install", installScriptlet, install, installScript );
        postinstallScriptlet = passiveScripts( "postinstall", postinstallScriptlet, postinstall, postinstallScript );
        preremoveScriptlet = passiveScripts( "preremove", preremoveScriptlet, preremove, preremoveScript );
        postremoveScriptlet = passiveScripts( "postremove", postremoveScriptlet, postremove, postremoveScript );
        verifyScriptlet = passiveScripts( "verify", verifyScriptlet, verify, verifyScript );
        cleanScriptlet = passiveScripts( "clean", cleanScriptlet, clean, cleanScript );

        if ( ( changelog == null ) && ( changelogFile != null ) )
        {
            if ( !changelogFile.exists() )
            {
                log.debug( changelogFile.getAbsolutePath() + " does not exist - ignoring" );
            }
            else
            {
                try
                {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br = new BufferedReader( new FileReader( changelogFile ) );
                    while ( br.ready() )
                    {
                        String line = br.readLine();
                        sb.append( line );
                        sb.append( '\n' );
                    }
                    br.close();
                    changelog = sb.toString();
                }
                catch ( Throwable t )
                {
                    throw new MojoExecutionException( "Unable to read " + changelogFile.getAbsolutePath(), t );
                }
            }
        }

        // generate copyright text if not set
        if ( copyright == null )
        {
            copyright = generateCopyrightText();
        }

        // if this package obsoletes any packages, make sure those packages are added to the provides list
        if ( obsoletes != null )
        {
            if ( provides == null )
            {
                provides = obsoletes;
            }
            else
            {
                provides.addAll( obsoletes );
            }
        }

        processDefineStatements();
    }

    /**
     * Put all name/value pairs in {@link #defineStatements} in {@link #macroKeyToValue}.
     * 
     * @since 2.1-alpha-1
     */
    private void processDefineStatements()
    {
        if ( defineStatements == null )
        {
            return;
        }
        for ( String define : defineStatements )
        {
            String[] parts = define.split( " " );
            if ( parts.length == 2 )
            {
                macroKeyToValue.put( parts[0], parts[1] );
            }
        }
    }

    /**
     * Validate that {@link #workarea} is a {@link File#isDirectory() directory} and that the
     * {@link File#getAbsolutePath()} does not contain any spaces.
     * 
     * @throws MojoExecutionException
     */
    private void validateWorkarea()
        throws MojoExecutionException
    {
        if ( !workarea.isDirectory() )
        {
            throw new MojoExecutionException( workarea + " is not a directory" );
        }

        if ( workarea.getAbsolutePath().trim().indexOf( " " ) != -1 )
        {
            throw new MojoExecutionException( workarea + " contains a space in path" );
        }
    }

    /**
     * Handles the <i>scriptlet</i> and corresponding deprecated <i>script</i> and <i>file</i>. Will return a
     * {@link Scriptlet} representing the coalesced stated.
     */
    private Scriptlet passiveScripts( final String name, Scriptlet scriptlet, final String script, final File file )
    {
        if ( scriptlet == null && ( script != null || file != null ) )
        {
            scriptlet = new Scriptlet();
            scriptlet.setScript( script );
            scriptlet.setScriptFile( file );
            getLog().warn( "Deprecated <" + name + "> and/or <" + name + "Script> used - should use <" + name
                               + "Scriptlet>" );
        }

        return scriptlet;
    }

    /**
     * Determines the actual value for the <i>macro</i>. Will check both {@link #defineStatements} and
     * {@link RPMHelper#evaluateMacro(String)}.
     * 
     * @param macro The macro to evaluate.
     * @return The literal value or name of macro if it has no value.
     * @throws MojoExecutionException
     * @since 2.1-alpha-1
     */
    String evaluateMacro( String macro )
        throws MojoExecutionException
    {
        if ( macroKeyToValue.containsKey( macro ) )
        {
            return macroKeyToValue.get( macro );
        }

        final String value = helper.evaluateMacro( macro );
        macroKeyToValue.put( macro, value );

        return value;
    }

    /**
     * Write the SPEC file.
     * 
     * @throws MojoExecutionException if an error occurs writing the file
     */
    private void writeSpecFile()
        throws MojoExecutionException
    {
        File f = new File( workarea, "SPECS" );
        File specf = new File( f, name + ".spec" );

        try
        {
            getLog().info( "Creating spec file " + specf.getAbsolutePath() );
            PrintWriter spec = new PrintWriter( new FileWriter( specf ) );
            try
            {
                new SpecWriter( this, spec ).writeSpecFile();
            }
            finally
            {
                spec.close();
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to write " + specf.getAbsolutePath(), e );
        }
    }

    /**
     * Generates the copyright text from {@link MavenProject#getOrganization()} and
     * {@link MavenProject#getInceptionYear()}.
     * 
     * @return Generated copyright text from the organization name and inception year.
     */
    private String generateCopyrightText()
    {
        String copyrightText;
        String year = project.getInceptionYear();
        String organization = project.getOrganization() == null ? null : project.getOrganization().getName();
        if ( ( year != null ) && ( organization != null ) )
        {
            copyrightText = year + " " + organization;
        }
        else
        {
            copyrightText = year == null ? organization : year;
        }
        return copyrightText;
    }

    /**
     * @return Returns the {@link #linkTargetToSources}.
     */
    final Map<String, List<SoftlinkSource>> getLinkTargetToSources()
    {
        return this.linkTargetToSources;
    }

    /**
     * @return Returns the {@link #name}.
     */
    final String getName()
    {
        return this.name;
    }

    /**
     * @return Returns the {@link #release}.
     */
    public final String getRelease()
    {
        return this.release;
    }

    /**
     * @return Returns the {@link #description}.
     */
    final String getDescription()
    {
        return this.description;
    }

    /**
     * @return Returns the {@link #summary}.
     */
    final String getSummary()
    {
        return this.summary;
    }

    /**
     * @return Returns the {@link #copyright}.
     */
    final String getCopyright()
    {
        return this.copyright;
    }

    /**
     * @return Returns the {@link #distribution}.
     */
    final String getDistribution()
    {
        return this.distribution;
    }

    /**
     * @return Returns the {@link #icon}.
     */
    final File getIcon()
    {
        return this.icon;
    }

    /**
     * @return Returns the {@link #vendor}.
     */
    final String getVendor()
    {
        return this.vendor;
    }

    /**
     * @return Returns the {@link #url}.
     */
    final String getUrl()
    {
        return this.url;
    }

    /**
     * @return Returns the {@link #group}.
     */
    final String getGroup()
    {
        return this.group;
    }

    /**
     * @return Returns the {@link #packager}.
     */
    final String getPackager()
    {
        return this.packager;
    }

    /**
     * @return Returns the {@link #autoProvides}.
     */
    final boolean isAutoProvides()
    {
        return this.autoProvides;
    }

    /**
     * @return Returns the {@link #autoRequires}.
     */
    final boolean isAutoRequires()
    {
        return this.autoRequires;
    }

    /**
     * @return Returns the {@link #provides}.
     */
    final LinkedHashSet<String> getProvides()
    {
        return this.provides;
    }

    /**
     * @return Returns the {@link #requires}.
     */
    final LinkedHashSet<String> getRequires()
    {
        return this.requires;
    }

    /**
     * @return Returns the {@link #prereqs}.
     */
    final LinkedHashSet<String> getPrereqs()
    {
        return this.prereqs;
    }

    /**
     * @return Returns the {@link #obsoletes}.
     */
    final LinkedHashSet<String> getObsoletes()
    {
        return this.obsoletes;
    }

    /**
     * @return Returns the {@link #conflicts}.
     */
    final LinkedHashSet<String> getConflicts()
    {
        return this.conflicts;
    }

    /**
     * @return Returns the {@link #prefix}.
     */
    final String getPrefix()
    {
        return this.prefix;
    }

    /**
     * @return Returns the {@link #mappings}.
     */
    final List<Mapping> getMappings()
    {
        return this.mappings;
    }

    /**
     * @return Returns the {@link #prepareScriptlet}.
     */
    final Scriptlet getPrepareScriptlet()
    {
        return this.prepareScriptlet;
    }

    /**
     * @return Returns the {@link #preinstallScriptlet}.
     */
    final Scriptlet getPreinstallScriptlet()
    {
        return this.preinstallScriptlet;
    }

    /**
     * @return Returns the {@link #postinstallScriptlet}.
     */
    final Scriptlet getPostinstallScriptlet()
    {
        return this.postinstallScriptlet;
    }

    /**
     * @return Returns the {@link #installScriptlet}.
     */
    final Scriptlet getInstallScriptlet()
    {
        return this.installScriptlet;
    }

    /**
     * @return Returns the {@link #preremoveScriptlet}.
     */
    final Scriptlet getPreremoveScriptlet()
    {
        return this.preremoveScriptlet;
    }

    /**
     * @return Returns the {@link #postremoveScriptlet}.
     */
    final Scriptlet getPostremoveScriptlet()
    {
        return this.postremoveScriptlet;
    }

    /**
     * @return Returns the {@link #verifyScriptlet}.
     */
    final Scriptlet getVerifyScriptlet()
    {
        return this.verifyScriptlet;
    }

    /**
     * @return Returns the {@link #cleanScriptlet}.
     */
    final Scriptlet getCleanScriptlet()
    {
        return this.cleanScriptlet;
    }

    /**
     * @return Returns the {@link #pretransScriptlet}.
     */
    final Scriptlet getPretransScriptlet()
    {
        return this.pretransScriptlet;
    }

    /**
     * @return Returns the {@link #posttransScriptlet}.
     */
    final Scriptlet getPosttransScriptlet()
    {
        return this.posttransScriptlet;
    }

    /**
     * @return Returns the {@link #triggers}.
     */
    final List<BaseTrigger> getTriggers()
    {
        return this.triggers;
    }

    /**
     * @return Returns the {@link #defineStatements}.
     */
    final List<String> getDefineStatements()
    {
        return this.defineStatements;
    }

    /**
     * @return Returns the {@link #defaultFilemode}.
     */
    final String getDefaultFilemode()
    {
        return this.defaultFilemode;
    }

    /**
     * @return Returns the {@link #defaultDirmode}.
     */
    final String getDefaultDirmode()
    {
        return this.defaultDirmode;
    }

    /**
     * @return Returns the {@link #defaultUsername}.
     */
    final String getDefaultUsername()
    {
        return this.defaultUsername;
    }

    /**
     * @return Returns the {@link #defaultGroupname}.
     */
    final String getDefaultGroupname()
    {
        return this.defaultGroupname;
    }

    /**
     * @return Returns the {@link #buildroot}.
     */
    final File getBuildroot()
    {
        return this.buildroot;
    }

    /**
     * @return Returns the {@link #rpmBuildroot}.
     */
    final File getRPMBuildroot()
    {
        return this.rpmBuildroot;
    }

    /**
     * @inheritDoc
     */
    public final String getVersion()
    {
        return this.projversion;
    }

    /**
     * @return Returns the {@link #changelog}.
     */
    final String getChangelog()
    {
        return this.changelog;
    }

    /**
     * @return Returns the {@link #targetArch}.
     */
    final String getTargetArch()
    {
        return this.targetArch;
    }

    /**
     * @return Returns the {@link #targetOS}.
     */
    final String getTargetOS()
    {
        return this.targetOS;
    }

    /**
     * @return Returns the {@link #targetVendor}.
     */
    final String getTargetVendor()
    {
        return this.targetVendor;
    }

    /**
     * @return Returns the {@link #keyname}.
     */
    final String getKeyname()
    {
        return this.keyname;
    }

    /**
     * @return Returns the {@link #keyPassphrase}.
     */
    final Passphrase getKeyPassphrase()
    {
        return this.keyPassphrase;
    }

    /**
     * @return Returns the {@link #workarea}.
     */
    final File getWorkarea()
    {
        return this.workarea;
    }

    /**
     * @return Returns the {@link #artifact}.
     */
    final Artifact getArtifact()
    {
        return this.artifact;
    }

    /**
     * @return Returns the {@link #attachedArtifacts}.
     */
    final List<Artifact> getAttachedArtifacts()
    {
        return this.attachedArtifacts;
    }

    /**
     * Returns the {@link FileUtils.FilterWrapper wrappers} to use for filtering resources.
     * 
     * @return Returns the {@code FilterWrapper}s to use for filtering resources.
     */
    final List<FileUtils.FilterWrapper> getFilterWrappers()
    {
        return this.defaultFilterWrappers;
    }
}