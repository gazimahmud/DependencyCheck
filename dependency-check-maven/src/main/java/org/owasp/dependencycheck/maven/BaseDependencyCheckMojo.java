/*
 * This file is part of dependency-check-maven.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2014 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.maven;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.shared.artifact.ArtifactCoordinate;
import org.apache.maven.shared.artifact.TransferUtils;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.data.nexus.MavenArtifact;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Identifier;
import org.owasp.dependencycheck.dependency.Vulnerability;
import org.owasp.dependencycheck.exception.DependencyNotFoundException;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.utils.Filter;
import org.owasp.dependencycheck.utils.Settings;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

/**
 *
 * @author Jeremy Long
 */
public abstract class BaseDependencyCheckMojo extends AbstractMojo implements MavenReport {

    //<editor-fold defaultstate="collapsed" desc="Private fields">
    /**
     * The properties file location.
     */
    private static final String PROPERTIES_FILE = "mojo.properties";
    /**
     * System specific new line character.
     */
    private static final String NEW_LINE = System.getProperty("line.separator", "\n").intern();
    /**
     * A flag indicating whether or not the Maven site is being generated.
     */
    private boolean generatingSite = false;
    //</editor-fold>
    // <editor-fold defaultstate="collapsed" desc="Maven bound parameters and components">
    /**
     * Sets whether or not the external report format should be used.
     */
    @Parameter(property = "metaFileName", defaultValue = "dependency-check.ser", required = true)
    private String dataFileName;
    /**
     * Sets whether or not the external report format should be used.
     */
    @Parameter(property = "failOnError", defaultValue = "true", required = true)
    private boolean failOnError;

    /**
     * The Maven Project Object.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;
    /**
     * List of Maven project of the current build
     */
    @Parameter(readonly = true, required = true, property = "reactorProjects")
    private List<MavenProject> reactorProjects;
    /**
     * The entry point towards a Maven version independent way of resolving
     * artifacts (handles both Maven 3.0 Sonatype and Maven 3.1+ eclipse Aether
     * implementations).
     */
    @Component
    private ArtifactResolver artifactResolver;

    /**
     * The Maven Session.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Remote repositories which will be searched for artifacts.
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;

    /**
     * Component within Maven to build the dependency graph.
     */
    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * The output directory. This generally maps to "target".
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;
    /**
     * Specifies the destination directory for the generated Dependency-Check
     * report. This generally maps to "target/site".
     */
    @Parameter(property = "project.reporting.outputDirectory", required = true)
    private File reportOutputDirectory;
    /**
     * Specifies if the build should be failed if a CVSS score above a specified
     * level is identified. The default is 11 which means since the CVSS scores
     * are 0-10, by default the build will never fail.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "failBuildOnCVSS", defaultValue = "11", required = true)
    private float failBuildOnCVSS = 11;
    /**
     * Fail the build if any dependency has a vulnerability listed.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "failBuildOnAnyVulnerability", defaultValue = "false", required = true)
    private boolean failBuildOnAnyVulnerability = false;
    /**
     * Sets whether auto-updating of the NVD CVE/CPE data is enabled. It is not
     * recommended that this be turned to false. Default is true.
     */
    @Parameter(property = "autoUpdate")
    private Boolean autoUpdate;
    /**
     * Sets whether Experimental analyzers are enabled. Default is false.
     */
    @Parameter(property = "enableExperimental")
    private Boolean enableExperimental;
    /**
     * Generate aggregate reports in multi-module projects.
     *
     * @deprecated use the aggregate goal instead
     */
    @Parameter(property = "aggregate")
    @Deprecated
    private Boolean aggregate;
    /**
     * The report format to be generated (HTML, XML, VULN, ALL). This
     * configuration option has no affect if using this within the Site plug-in
     * unless the externalReport is set to true. Default is HTML.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "format", defaultValue = "HTML", required = true)
    private String format = "HTML";
    /**
     * The Maven settings.
     */
    @Parameter(property = "mavenSettings", defaultValue = "${settings}", required = false)
    private org.apache.maven.settings.Settings mavenSettings;

    /**
     * The maven settings proxy id.
     */
    @Parameter(property = "mavenSettingsProxyId", required = false)
    private String mavenSettingsProxyId;

    /**
     * The Connection Timeout.
     */
    @Parameter(property = "connectionTimeout", defaultValue = "", required = false)
    private String connectionTimeout;
    /**
     * The path to the suppression file.
     */
    @Parameter(property = "suppressionFile", defaultValue = "", required = false)
    private String suppressionFile;

    /**
     * The path to the hints file.
     */
    @Parameter(property = "hintsFile", defaultValue = "", required = false)
    private String hintsFile;

    /**
     * Flag indicating whether or not to show a summary in the output.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "showSummary", defaultValue = "true", required = false)
    private boolean showSummary = true;

    /**
     * Whether or not the Jar Analyzer is enabled.
     */
    @Parameter(property = "jarAnalyzerEnabled", required = false)
    private Boolean jarAnalyzerEnabled;

    /**
     * Whether or not the Archive Analyzer is enabled.
     */
    @Parameter(property = "archiveAnalyzerEnabled", required = false)
    private Boolean archiveAnalyzerEnabled;

    /**
     * Sets whether the Python Distribution Analyzer will be used.
     */
    @Parameter(property = "pyDistributionAnalyzerEnabled", required = false)
    private Boolean pyDistributionAnalyzerEnabled;
    /**
     * Sets whether the Python Package Analyzer will be used.
     */
    @Parameter(property = "pyPackageAnalyzerEnabled", required = false)
    private Boolean pyPackageAnalyzerEnabled;
    /**
     * Sets whether the Ruby Gemspec Analyzer will be used.
     */
    @Parameter(property = "rubygemsAnalyzerEnabled", required = false)
    private Boolean rubygemsAnalyzerEnabled;
    /**
     * Sets whether or not the openssl Analyzer should be used.
     */
    @Parameter(property = "opensslAnalyzerEnabled", required = false)
    private Boolean opensslAnalyzerEnabled;
    /**
     * Sets whether or not the CMake Analyzer should be used.
     */
    @Parameter(property = "cmakeAnalyzerEnabled", required = false)
    private Boolean cmakeAnalyzerEnabled;
    /**
     * Sets whether or not the autoconf Analyzer should be used.
     */
    @Parameter(property = "autoconfAnalyzerEnabled", required = false)
    private Boolean autoconfAnalyzerEnabled;
    /**
     * Sets whether or not the PHP Composer Lock File Analyzer should be used.
     */
    @Parameter(property = "composerAnalyzerEnabled", required = false)
    private Boolean composerAnalyzerEnabled;
    /**
     * Sets whether or not the Node.js Analyzer should be used.
     */
    @Parameter(property = "nodeAnalyzerEnabled", required = false)
    private Boolean nodeAnalyzerEnabled;
    /**
     * Sets whether or not the Node Security Project Analyzer should be used.
     */
    @Parameter(property = "nspAnalyzerEnabled", required = false)
    private Boolean nspAnalyzerEnabled;

    /**
     * Whether or not the .NET Assembly Analyzer is enabled.
     */
    @Parameter(property = "assemblyAnalyzerEnabled", required = false)
    private Boolean assemblyAnalyzerEnabled;

    /**
     * Whether or not the .NET Nuspec Analyzer is enabled.
     */
    @Parameter(property = "nuspecAnalyzerEnabled", required = false)
    private Boolean nuspecAnalyzerEnabled;

    /**
     * Whether or not the Central Analyzer is enabled.
     */
    @Parameter(property = "centralAnalyzerEnabled", required = false)
    private Boolean centralAnalyzerEnabled;

    /**
     * Whether or not the Nexus Analyzer is enabled.
     */
    @Parameter(property = "nexusAnalyzerEnabled", required = false)
    private Boolean nexusAnalyzerEnabled;

    /**
     * Whether or not the Ruby Bundle Audit Analyzer is enabled.
     */
    @Parameter(property = "bundleAuditAnalyzerEnabled", required = false)
    private Boolean bundleAuditAnalyzerEnabled;

    /**
     * Sets the path for the bundle-audit binary.
     */
    @Parameter(property = "bundleAuditPath", defaultValue = "", required = false)
    private String bundleAuditPath;

    /**
     * Whether or not the CocoaPods Analyzer is enabled.
     */
    @Parameter(property = "cocoapodsAnalyzerEnabled", required = false)
    private Boolean cocoapodsAnalyzerEnabled;

    /**
     * Whether or not the Swift package Analyzer is enabled.
     */
    @Parameter(property = "swiftPackageManagerAnalyzerEnabled", required = false)
    private Boolean swiftPackageManagerAnalyzerEnabled;

    /**
     * The URL of a Nexus server's REST API end point
     * (http://domain/nexus/service/local).
     */
    @Parameter(property = "nexusUrl", required = false)
    private String nexusUrl;
    /**
     * Whether or not the configured proxy is used to connect to Nexus.
     */
    @Parameter(property = "nexusUsesProxy", required = false)
    private Boolean nexusUsesProxy;
    /**
     * The database connection string.
     */
    @Parameter(property = "connectionString", defaultValue = "", required = false)
    private String connectionString;

    /**
     * The database driver name. An example would be org.h2.Driver.
     */
    @Parameter(property = "databaseDriverName", defaultValue = "", required = false)
    private String databaseDriverName;
    /**
     * The path to the database driver if it is not on the class path.
     */
    @Parameter(property = "databaseDriverPath", defaultValue = "", required = false)
    private String databaseDriverPath;
    /**
     * The server id in the settings.xml; used to retrieve encrypted passwords
     * from the settings.xml.
     */
    @Parameter(property = "serverId", defaultValue = "", required = false)
    private String serverId;
    /**
     * A reference to the settings.xml settings.
     */
    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    private org.apache.maven.settings.Settings settingsXml;
    /**
     * The security dispatcher that can decrypt passwords in the settings.xml.
     */
    @Component(role = SecDispatcher.class, hint = "default")
    private SecDispatcher securityDispatcher;
    /**
     * The database user name.
     */
    @Parameter(property = "databaseUser", defaultValue = "", required = false)
    private String databaseUser;
    /**
     * The password to use when connecting to the database.
     */
    @Parameter(property = "databasePassword", defaultValue = "", required = false)
    private String databasePassword;
    /**
     * A comma-separated list of file extensions to add to analysis next to jar,
     * zip, ....
     */
    @Parameter(property = "zipExtensions", required = false)
    private String zipExtensions;
    /**
     * Skip Dependency Check altogether.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "dependency-check.skip", defaultValue = "false", required = false)
    private boolean skip = false;
    /**
     * Skip Analysis for Test Scope Dependencies.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "skipTestScope", defaultValue = "true", required = false)
    private boolean skipTestScope = true;
    /**
     * Skip Analysis for Runtime Scope Dependencies.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "skipRuntimeScope", defaultValue = "false", required = false)
    private boolean skipRuntimeScope = false;
    /**
     * Skip Analysis for Provided Scope Dependencies.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "skipProvidedScope", defaultValue = "false", required = false)
    private boolean skipProvidedScope = false;

    /**
     * Skip Analysis for Provided Scope Dependencies.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "skipSystemScope", defaultValue = "false", required = false)
    private boolean skipSystemScope = false;

    /**
     * Skip analysis for dependencies which type matches this regular expression.
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "skipArtifactType", required = false)
    private String skipArtifactType;

    /**
     * The data directory, hold DC SQL DB.
     */
    @Parameter(property = "dataDirectory", defaultValue = "", required = false)
    private String dataDirectory;
    /**
     * Data Mirror URL for CVE 1.2.
     */
    @Parameter(property = "cveUrl12Modified", defaultValue = "", required = false)
    private String cveUrl12Modified;
    /**
     * Data Mirror URL for CVE 2.0.
     */
    @Parameter(property = "cveUrl20Modified", defaultValue = "", required = false)
    private String cveUrl20Modified;
    /**
     * Base Data Mirror URL for CVE 1.2.
     */
    @Parameter(property = "cveUrl12Base", defaultValue = "", required = false)
    private String cveUrl12Base;
    /**
     * Data Mirror URL for CVE 2.0.
     */
    @Parameter(property = "cveUrl20Base", defaultValue = "", required = false)
    private String cveUrl20Base;
    /**
     * Optionally skip excessive CVE update checks for a designated duration in
     * hours.
     */
    @Parameter(property = "cveValidForHours", defaultValue = "", required = false)
    private Integer cveValidForHours;

    /**
     * The path to mono for .NET Assembly analysis on non-windows systems.
     */
    @Parameter(property = "pathToMono", defaultValue = "", required = false)
    private String pathToMono;

    /**
     * The Proxy URL.
     *
     * @deprecated Please use mavenSettings instead
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "proxyUrl", defaultValue = "", required = false)
    @Deprecated
    private String proxyUrl = null;
    /**
     * Sets whether or not the external report format should be used.
     *
     * @deprecated the internal report is no longer supported
     */
    @SuppressWarnings("CanBeFinal")
    @Parameter(property = "externalReport")
    @Deprecated
    private String externalReport = null;

    /**
     * The artifact scope filter.
     */
    private Filter<String> artifactScopeExcluded;

    /**
     * Filter for artifact type.
     */
    private Filter<String> artifactTypeExcluded;


    // </editor-fold>
    //<editor-fold defaultstate="collapsed" desc="Base Maven implementation">
    /**
     * Executes dependency-check.
     *
     * @throws MojoExecutionException thrown if there is an exception executing
     * the mojo
     * @throws MojoFailureException thrown if dependency-check failed the build
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        generatingSite = false;
        if (skip) {
            getLog().info("Skipping " + getName(Locale.US));
        } else {
            validateAggregate();
            project.setContextValue(getOutputDirectoryContextKey(), this.outputDirectory);
            runCheck();
        }
    }

    /**
     * Checks if the aggregate configuration parameter has been set to true. If
     * it has a MojoExecutionException is thrown because the aggregate
     * configuration parameter is no longer supported.
     *
     * @throws MojoExecutionException thrown if aggregate is set to true
     */
    private void validateAggregate() throws MojoExecutionException {
        if (aggregate != null && aggregate) {
            final String msg = "Aggregate configuration detected - as of dependency-check 1.2.8 this no longer supported. "
                    + "Please use the aggregate goal instead.";
            throw new MojoExecutionException(msg);
        }
    }

    /**
     * Generates the Dependency-Check Site Report.
     *
     * @param sink the sink to write the report to
     * @param locale the locale to use when generating the report
     * @throws MavenReportException if a maven report exception occurs
     * @deprecated use
     * {@link #generate(org.apache.maven.doxia.sink.Sink, java.util.Locale)}
     * instead.
     */
    @Override
    @Deprecated
    public final void generate(@SuppressWarnings("deprecation") org.codehaus.doxia.sink.Sink sink, Locale locale) throws MavenReportException {
        generate((Sink) sink, locale);
    }

    /**
     * Returns true if the Maven site is being generated.
     *
     * @return true if the Maven site is being generated
     */
    protected boolean isGeneratingSite() {
        return generatingSite;
    }

    /**
     * Returns the connection string.
     *
     * @return the connection string
     */
    protected String getConnectionString() {
        return connectionString;
    }

    /**
     * Returns if the mojo should fail the build if an exception occurs.
     *
     * @return whether or not the mojo should fail the build
     */
    protected boolean isFailOnError() {
        return failOnError;
    }

    /**
     * Generates the Dependency-Check Site Report.
     *
     * @param sink the sink to write the report to
     * @param locale the locale to use when generating the report
     * @throws MavenReportException if a maven report exception occurs
     */
    public void generate(Sink sink, Locale locale) throws MavenReportException {
        generatingSite = true;
        try {
            validateAggregate();
        } catch (MojoExecutionException ex) {
            throw new MavenReportException(ex.getMessage());
        }
        project.setContextValue(getOutputDirectoryContextKey(), getReportOutputDirectory());
        try {
            runCheck();
        } catch (MojoExecutionException ex) {
            throw new MavenReportException(ex.getMessage(), ex);
        } catch (MojoFailureException ex) {
            getLog().warn("Vulnerabilities were identifies that exceed the CVSS threshold for failing the build");
        }
    }

    /**
     * Returns the correct output directory depending on if a site is being
     * executed or not.
     *
     * @return the directory to write the report(s)
     * @throws MojoExecutionException thrown if there is an error loading the
     * file path
     */
    protected File getCorrectOutputDirectory() throws MojoExecutionException {
        return getCorrectOutputDirectory(this.project);
    }

    /**
     * Returns the correct output directory depending on if a site is being
     * executed or not.
     *
     * @param current the Maven project to get the output directory from
     * @return the directory to write the report(s)
     */
    protected File getCorrectOutputDirectory(MavenProject current) {
        final Object obj = current.getContextValue(getOutputDirectoryContextKey());
        if (obj != null && obj instanceof File) {
            return (File) obj;
        }
        File target = new File(current.getBuild().getDirectory());
        if (target.getParentFile() != null && "target".equals(target.getParentFile().getName())) {
            target = target.getParentFile();
        }
        return target;
    }

    /**
     * Scans the project's artifacts and adds them to the engine's dependency
     * list.
     *
     * @param project the project to scan the dependencies of
     * @param engine the engine to use to scan the dependencies
     * @return a collection of exceptions that may have occurred while resolving
     * and scanning the dependencies
     */
    protected ExceptionCollection scanArtifacts(MavenProject project, Engine engine) {
        try {
            final DependencyNode dn = dependencyGraphBuilder.buildDependencyGraph(project, null, reactorProjects);
            final ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest();
            return collectDependencies(engine, project, dn.getChildren(), buildingRequest);
        } catch (DependencyGraphBuilderException ex) {
            final String msg = String.format("Unable to build dependency graph on project %s", project.getName());
            getLog().debug(msg, ex);
            return new ExceptionCollection(msg, ex);
        }
    }

    /**
     * Resolves the projects artifacts using Aether and scans the resulting
     * dependencies.
     *
     * @param engine the core dependency-check engine
     * @param project the project being scanned
     * @param nodes the list of dependency nodes, generally obtained via the
     * DependencyGraphBuilder
     * @param buildingRequest the Maven project building request
     * @return a collection of exceptions that may have occurred while resolving
     * and scanning the dependencies
     */
    private ExceptionCollection collectDependencies(Engine engine, MavenProject project,
            List<DependencyNode> nodes, ProjectBuildingRequest buildingRequest) {
        ExceptionCollection exCol = null;
        for (DependencyNode dependencyNode : nodes) {
            if (artifactScopeExcluded.passes(dependencyNode.getArtifact().getScope()) ||
                artifactTypeExcluded.passes(dependencyNode.getArtifact().getType())) {
                continue;
            }
            exCol = collectDependencies(engine, project, dependencyNode.getChildren(), buildingRequest);
            try {
                boolean isResolved = false;
                File artifactFile = null;
                String artifactId = null;
                String groupId = null;
                String version = null;
                if (org.apache.maven.artifact.Artifact.SCOPE_SYSTEM.equals(dependencyNode.getArtifact().getScope())) {
                    for (org.apache.maven.model.Dependency d : project.getDependencies()) {
                        final Artifact a = dependencyNode.getArtifact();
                        if (d.getSystemPath() != null && artifactsMatch(d, a)) {

                            artifactFile = new File(d.getSystemPath());
                            isResolved = artifactFile.isFile();
                            groupId = a.getGroupId();
                            artifactId = a.getArtifactId();
                            version = a.getVersion();
                            break;
                        }
                    }
                    if (!isResolved) {
                        getLog().error("Unable to resolve system scoped dependency: " + dependencyNode.toNodeString());
                        exCol.addException(new DependencyNotFoundException("Unable to resolve system scoped dependency: " + dependencyNode.toNodeString()));
                    }
                } else {
                    final ArtifactCoordinate coordinate = TransferUtils.toArtifactCoordinate(dependencyNode.getArtifact());
                    final Artifact result = artifactResolver.resolveArtifact(buildingRequest, coordinate).getArtifact();
                    isResolved = result.isResolved();
                    artifactFile = result.getFile();
                    groupId = result.getGroupId();
                    artifactId = result.getArtifactId();
                    version = result.getVersion();
                }
                if (isResolved && artifactFile != null) {
                    final List<Dependency> deps = engine.scan(artifactFile.getAbsoluteFile(),
                            project.getName() + ":" + dependencyNode.getArtifact().getScope());
                    if (deps != null) {
                        if (deps.size() == 1) {
                            final Dependency d = deps.get(0);
                            if (d != null) {
                                final MavenArtifact ma = new MavenArtifact(groupId, artifactId, version);
                                d.addAsEvidence("pom", ma, Confidence.HIGHEST);
                                if (getLog().isDebugEnabled()) {
                                    getLog().debug(String.format("Adding project reference %s on dependency %s",
                                            project.getName(), d.getDisplayFileName()));
                                }
                            }
                        } else if (getLog().isDebugEnabled()) {
                            final String msg = String.format("More than 1 dependency was identified in first pass scan of '%s' in project %s",
                                    dependencyNode.getArtifact().getId(), project.getName());
                            getLog().debug(msg);
                        }
                    } else {
                        final String msg = String.format("Error resolving '%s' in project %s",
                                dependencyNode.getArtifact().getId(), project.getName());
                        if (exCol == null) {
                            exCol = new ExceptionCollection();
                        }
                        getLog().error(msg);
                    }
                } else {
                    final String msg = String.format("Unable to resolve '%s' in project %s",
                            dependencyNode.getArtifact().getId(), project.getName());
                    getLog().debug(msg);
                    if (exCol == null) {
                        exCol = new ExceptionCollection();
                    }
                }
            } catch (ArtifactResolverException ex) {
                if (exCol == null) {
                    exCol = new ExceptionCollection();
                }
                exCol.addException(ex);
            }
        }
        return exCol;
    }

    /**
     * Determines if the groupId, artifactId, and version of the Maven
     * dependency and artifact match.
     *
     * @param d the Maven dependency
     * @param a the Maven artifact
     * @return true if the groupId, artifactId, and version match
     */
    private static boolean artifactsMatch(org.apache.maven.model.Dependency d, Artifact a) {
        return (isEqualOrNull(a.getArtifactId(), d.getArtifactId()))
                && (isEqualOrNull(a.getGroupId(), d.getGroupId()))
                && (isEqualOrNull(a.getVersion(), d.getVersion()));
    }

    /**
     * Compares two strings for equality; if both strings are null they are
     * considered equal.
     *
     * @param left the first string to compare
     * @param right the second string to compare
     * @return true if the strings are equal or if they are both null; otherwise
     * false.
     */
    private static boolean isEqualOrNull(String left, String right) {
        return (left != null && left.equals(right)) || (left == null && right == null);
    }

    /**
     * @return Returns a new ProjectBuildingRequest populated from the current
     * session and the current project remote repositories, used to resolve
     * artifacts.
     */
    public ProjectBuildingRequest newResolveArtifactProjectBuildingRequest() {
        final ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setRemoteRepositories(remoteRepositories);
        return buildingRequest;
    }

    /**
     * Executes the dependency-check scan and generates the necessary report.
     *
     * @throws MojoExecutionException thrown if there is an exception running
     * the scan
     * @throws MojoFailureException thrown if dependency-check is configured to
     * fail the build
     */
    public abstract void runCheck() throws MojoExecutionException, MojoFailureException;

    /**
     * Sets the Reporting output directory.
     *
     * @param directory the output directory
     */
    @Override
    public void setReportOutputDirectory(File directory) {
        reportOutputDirectory = directory;
    }

    /**
     * Returns the report output directory.
     *
     * @return the report output directory
     */
    @Override
    public File getReportOutputDirectory() {
        return reportOutputDirectory;
    }

    /**
     * Returns the output directory.
     *
     * @return the output directory
     */
    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Returns whether this is an external report. This method always returns
     * true.
     *
     * @return <code>true</code>
     */
    @Override
    public final boolean isExternalReport() {
        return true;
    }

    /**
     * Returns the output name.
     *
     * @return the output name
     */
    @Override
    public String getOutputName() {
        if ("HTML".equalsIgnoreCase(this.format) || "ALL".equalsIgnoreCase(this.format)) {
            return "dependency-check-report";
        } else if ("XML".equalsIgnoreCase(this.format)) {
            return "dependency-check-report.xml#";
        } else if ("VULN".equalsIgnoreCase(this.format)) {
            return "dependency-check-vulnerability";
        } else if ("JSON".equalsIgnoreCase(this.format)) {
            return "dependency-check-report.json";
        } else if ("CSV".equalsIgnoreCase(this.format)) {
            return "dependency-check-report.csv";
        } else {
            getLog().warn("Unknown report format used during site generation.");
            return "dependency-check-report";
        }
    }

    /**
     * Returns the category name.
     *
     * @return the category name
     */
    @Override
    public String getCategoryName() {
        return MavenReport.CATEGORY_PROJECT_REPORTS;
    }
    //</editor-fold>

    /**
     * Initializes a new <code>Engine</code> that can be used for scanning.
     *
     * @return a newly instantiated <code>Engine</code>
     * @throws DatabaseException thrown if there is a database exception
     */
    protected Engine initializeEngine() throws DatabaseException {
        populateSettings();
        return new Engine();
    }

    /**
     * Takes the properties supplied and updates the dependency-check settings.
     * Additionally, this sets the system properties required to change the
     * proxy url, port, and connection timeout.
     */
    protected void populateSettings() {
        Settings.initialize();
        InputStream mojoProperties = null;
        try {
            mojoProperties = this.getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE);
            Settings.mergeProperties(mojoProperties);
        } catch (IOException ex) {
            getLog().warn("Unable to load the dependency-check ant task.properties file.");
            if (getLog().isDebugEnabled()) {
                getLog().debug("", ex);
            }
        } finally {
            if (mojoProperties != null) {
                try {
                    mojoProperties.close();
                } catch (IOException ex) {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("", ex);
                    }
                }
            }
        }
        Settings.setBooleanIfNotNull(Settings.KEYS.AUTO_UPDATE, autoUpdate);

        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_EXPERIMENTAL_ENABLED, enableExperimental);

        if (externalReport != null) {
            getLog().warn("The 'externalReport' option was set; this configuration option has been removed. "
                    + "Please update the dependency-check-maven plugin's configuration");
        }

        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            getLog().warn("Deprecated configuration detected, proxyUrl will be ignored; use the maven settings to configure the proxy instead");
        }
        final Proxy proxy = getMavenProxy();
        if (proxy != null) {
            Settings.setString(Settings.KEYS.PROXY_SERVER, proxy.getHost());
            Settings.setString(Settings.KEYS.PROXY_PORT, Integer.toString(proxy.getPort()));
            final String userName = proxy.getUsername();
            final String password = proxy.getPassword();
            Settings.setStringIfNotNull(Settings.KEYS.PROXY_USERNAME, userName);
            Settings.setStringIfNotNull(Settings.KEYS.PROXY_PASSWORD, password);
            Settings.setStringIfNotNull(Settings.KEYS.PROXY_NON_PROXY_HOSTS, proxy.getNonProxyHosts());
        }

        Settings.setStringIfNotEmpty(Settings.KEYS.CONNECTION_TIMEOUT, connectionTimeout);
        Settings.setStringIfNotEmpty(Settings.KEYS.SUPPRESSION_FILE, suppressionFile);
        Settings.setStringIfNotEmpty(Settings.KEYS.HINTS_FILE, hintsFile);

        //File Type Analyzer Settings
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_JAR_ENABLED, jarAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_NUSPEC_ENABLED, nuspecAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, centralAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_NEXUS_ENABLED, nexusAnalyzerEnabled);
        Settings.setStringIfNotEmpty(Settings.KEYS.ANALYZER_NEXUS_URL, nexusUrl);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_NEXUS_USES_PROXY, nexusUsesProxy);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_ASSEMBLY_ENABLED, assemblyAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, archiveAnalyzerEnabled);
        Settings.setStringIfNotEmpty(Settings.KEYS.ADDITIONAL_ZIP_EXTENSIONS, zipExtensions);
        Settings.setStringIfNotEmpty(Settings.KEYS.ANALYZER_ASSEMBLY_MONO_PATH, pathToMono);

        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_PYTHON_DISTRIBUTION_ENABLED, pyDistributionAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_PYTHON_PACKAGE_ENABLED, pyPackageAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_RUBY_GEMSPEC_ENABLED, rubygemsAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_OPENSSL_ENABLED, opensslAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_CMAKE_ENABLED, cmakeAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_AUTOCONF_ENABLED, autoconfAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_COMPOSER_LOCK_ENABLED, composerAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_NODE_PACKAGE_ENABLED, nodeAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_NSP_PACKAGE_ENABLED, nspAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_BUNDLE_AUDIT_ENABLED, bundleAuditAnalyzerEnabled);
        Settings.setStringIfNotNull(Settings.KEYS.ANALYZER_BUNDLE_AUDIT_PATH, bundleAuditPath);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_COCOAPODS_ENABLED, cocoapodsAnalyzerEnabled);
        Settings.setBooleanIfNotNull(Settings.KEYS.ANALYZER_SWIFT_PACKAGE_MANAGER_ENABLED, swiftPackageManagerAnalyzerEnabled);

        //Database configuration
        Settings.setStringIfNotEmpty(Settings.KEYS.DB_DRIVER_NAME, databaseDriverName);
        Settings.setStringIfNotEmpty(Settings.KEYS.DB_DRIVER_PATH, databaseDriverPath);
        Settings.setStringIfNotEmpty(Settings.KEYS.DB_CONNECTION_STRING, connectionString);

        if (databaseUser == null && databasePassword == null && serverId != null) {
            final Server server = settingsXml.getServer(serverId);
            if (server != null) {
                databaseUser = server.getUsername();
                try {
                    //The following fix was copied from:
                    //   https://github.com/bsorrentino/maven-confluence-plugin/blob/master/maven-confluence-reporting-plugin/src/main/java/org/bsc/maven/confluence/plugin/AbstractBaseConfluenceMojo.java
                    //
                    // FIX to resolve
                    // org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException:
                    // java.io.FileNotFoundException: ~/.settings-security.xml (No such file or directory)
                    //
                    if (securityDispatcher instanceof DefaultSecDispatcher) {
                        ((DefaultSecDispatcher) securityDispatcher).setConfigurationFile("~/.m2/settings-security.xml");
                    }

                    databasePassword = securityDispatcher.decrypt(server.getPassword());
                } catch (SecDispatcherException ex) {
                    if (ex.getCause() instanceof FileNotFoundException
                            || (ex.getCause() != null && ex.getCause().getCause() instanceof FileNotFoundException)) {
                        //maybe its not encrypted?
                        final String tmp = server.getPassword();
                        if (tmp.startsWith("{") && tmp.endsWith("}")) {
                            getLog().error(String.format(
                                    "Unable to decrypt the server password for server id '%s' in settings.xml%n\tCause: %s",
                                    serverId, ex.getMessage()));
                        } else {
                            databasePassword = tmp;
                        }
                    } else {
                        getLog().error(String.format(
                                "Unable to decrypt the server password for server id '%s' in settings.xml%n\tCause: %s",
                                serverId, ex.getMessage()));
                    }
                }
            } else {
                getLog().error(String.format("Server '%s' not found in the settings.xml file", serverId));
            }
        }

        Settings.setStringIfNotEmpty(Settings.KEYS.DB_USER, databaseUser);
        Settings.setStringIfNotEmpty(Settings.KEYS.DB_PASSWORD, databasePassword);
        Settings.setStringIfNotEmpty(Settings.KEYS.DATA_DIRECTORY, dataDirectory);

        Settings.setStringIfNotEmpty(Settings.KEYS.CVE_MODIFIED_12_URL, cveUrl12Modified);
        Settings.setStringIfNotEmpty(Settings.KEYS.CVE_MODIFIED_20_URL, cveUrl20Modified);
        Settings.setStringIfNotEmpty(Settings.KEYS.CVE_SCHEMA_1_2, cveUrl12Base);
        Settings.setStringIfNotEmpty(Settings.KEYS.CVE_SCHEMA_2_0, cveUrl20Base);
        Settings.setIntIfNotNull(Settings.KEYS.CVE_CHECK_VALID_FOR_HOURS, cveValidForHours);

        artifactScopeExcluded = new ArtifactScopeExcluded(skipTestScope, skipProvidedScope, skipSystemScope, skipRuntimeScope);
        artifactTypeExcluded = new ArtifactTypeExcluded(skipArtifactType);
    }

    /**
     * Returns the maven proxy.
     *
     * @return the maven proxy
     */
    private Proxy getMavenProxy() {
        if (mavenSettings != null) {
            final List<Proxy> proxies = mavenSettings.getProxies();
            if (proxies != null && !proxies.isEmpty()) {
                if (mavenSettingsProxyId != null) {
                    for (Proxy proxy : proxies) {
                        if (mavenSettingsProxyId.equalsIgnoreCase(proxy.getId())) {
                            return proxy;
                        }
                    }
                } else if (proxies.size() == 1) {
                    return proxies.get(0);
                } else {
                    getLog().warn("Multiple proxy definitions exist in the Maven settings. In the dependency-check "
                            + "configuration set the mavenSettingsProxyId so that the correct proxy will be used.");
                    throw new IllegalStateException("Ambiguous proxy definition");
                }
            }
        }
        return null;
    }

    /**
     * Returns a reference to the current project. This method is used instead
     * of auto-binding the project via component annotation in concrete
     * implementations of this. If the child has a
     * <code>@Component MavenProject project;</code> defined then the abstract
     * class (i.e. this class) will not have access to the current project (just
     * the way Maven works with the binding).
     *
     * @return returns a reference to the current project
     */
    protected MavenProject getProject() {
        return project;
    }

    /**
     * Returns the list of Maven Projects in this build.
     *
     * @return the list of Maven Projects in this build
     */
    protected List<MavenProject> getReactorProjects() {
        return reactorProjects;
    }

    /**
     * Returns the report format.
     *
     * @return the report format
     */
    protected String getFormat() {
        return format;
    }

    /**
     * Returns the artifact scope excluded filter.
     *
     * @return the artifact scope excluded filter
     */
    protected Filter<String> getArtifactScopeExcluded() {
        return artifactScopeExcluded;
    }

    //<editor-fold defaultstate="collapsed" desc="Methods to fail build or show summary">
    /**
     * Checks to see if a vulnerability has been identified with a CVSS score
     * that is above the threshold set in the configuration.
     *
     * @param dependencies the list of dependency objects
     * @throws MojoFailureException thrown if a CVSS score is found that is
     * higher then the threshold set
     */
    protected void checkForFailure(List<Dependency> dependencies) throws MojoFailureException {
        final StringBuilder ids = new StringBuilder();
        for (Dependency d : dependencies) {
            boolean addName = true;
            for (Vulnerability v : d.getVulnerabilities()) {
                if (failBuildOnAnyVulnerability || v.getCvssScore() >= failBuildOnCVSS) {
                    if (addName) {
                        addName = false;
                        ids.append(NEW_LINE).append(d.getFileName()).append(": ");
                        ids.append(v.getName());
                    } else {
                        ids.append(", ").append(v.getName());
                    }
                }
            }
        }
        if (ids.length() > 0) {
            final String msg;
            if (failBuildOnAnyVulnerability) {
                msg = String.format("%n%nOne or more dependencies were identified with vulnerabilities: %n%s%n%n"
                        + "See the dependency-check report for more details.%n%n", ids.toString());
            } else {
                msg = String.format("%n%nOne or more dependencies were identified with vulnerabilities that have a CVSS score greater than '%.1f': "
                        + "%n%s%n%nSee the dependency-check report for more details.%n%n", failBuildOnCVSS, ids.toString());
            }

            throw new MojoFailureException(msg);
        }
    }

    /**
     * Generates a warning message listing a summary of dependencies and their
     * associated CPE and CVE entries.
     *
     * @param mp the Maven project for which the summary is shown
     * @param dependencies a list of dependency objects
     */
    protected void showSummary(MavenProject mp, List<Dependency> dependencies) {
        if (showSummary) {
            final StringBuilder summary = new StringBuilder();
            for (Dependency d : dependencies) {
                boolean firstEntry = true;
                final StringBuilder ids = new StringBuilder();
                for (Vulnerability v : d.getVulnerabilities()) {
                    if (firstEntry) {
                        firstEntry = false;
                    } else {
                        ids.append(", ");
                    }
                    ids.append(v.getName());
                }
                if (ids.length() > 0) {
                    summary.append(d.getFileName()).append(" (");
                    firstEntry = true;
                    for (Identifier id : d.getIdentifiers()) {
                        if (firstEntry) {
                            firstEntry = false;
                        } else {
                            summary.append(", ");
                        }
                        summary.append(id.getValue());
                    }
                    summary.append(") : ").append(ids).append(NEW_LINE);
                }
            }
            if (summary.length() > 0) {
                final String msg = String.format("%n%n" + "One or more dependencies were identified with known vulnerabilities in %s:%n%n%s"
                        + "%n%nSee the dependency-check report for more details.%n%n", mp.getName(), summary.toString());
                getLog().warn(msg);
            }
        }
    }

    //</editor-fold>
    //<editor-fold defaultstate="collapsed" desc="Methods to read/write the serialized data file">
    /**
     * Returns the key used to store the path to the data file that is saved by
     * <code>writeDataFile()</code>. This key is used in the
     * <code>MavenProject.(set|get)ContextValue</code>.
     *
     * @return the key used to store the path to the data file
     */
    protected String getDataFileContextKey() {
        return "dependency-check-path-" + dataFileName;
    }

    /**
     * Returns the key used to store the path to the output directory. When
     * generating the report in the <code>executeAggregateReport()</code> the
     * output directory should be obtained by using this key.
     *
     * @return the key used to store the path to the output directory
     */
    protected String getOutputDirectoryContextKey() {
        return "dependency-output-dir-" + dataFileName;
    }

    //</editor-fold>
}
