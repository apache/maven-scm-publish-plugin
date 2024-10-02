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
package org.apache.maven.plugins.scmpublish;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.scm.CommandParameter;
import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmBranch;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmResult;
import org.apache.maven.scm.command.add.AddScmResult;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmUrlUtils;
import org.apache.maven.scm.provider.svn.AbstractSvnScmProvider;
import org.apache.maven.scm.provider.svn.repository.SvnScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.config.ReleaseDescriptorBuilder;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;
import org.apache.maven.shared.utils.logging.MessageUtils;

/**
 * Base class for the scm-publish mojos.
 */
public abstract class AbstractScmPublishMojo extends AbstractMojo {
    // CHECKSTYLE_OFF: LineLength
    /**
     * URL of the target SCM repository to publish to in format
     * {@code scm:<scm_provider><delimiter><provider_specific_part>}.
     * <p>
     * Example:
     * {@code scm:svn:https://svn.apache.org/repos/infra/websites/production/maven/content/plugins/maven-scm-publish-plugin-LATEST/}
     *
     * @see <a href="https://maven.apache.org/scm/scm-url-format.html">SCM URL Format</a>
     */
    // CHECKSTYLE_ON: LineLength
    @Parameter(
            property = "scmpublish.pubScmUrl",
            defaultValue = "${project.distributionManagement.site.url}",
            required = true)
    protected String pubScmUrl;

    /**
     * If the {@link AbstractScmPublishMojo#checkoutDirectory} exists and this flag is activated, the plugin will try an SCM-update instead
     * of deleting first and then doing a fresh checkout.
     */
    @Parameter(property = "scmpublish.tryUpdate", defaultValue = "false")
    protected boolean tryUpdate;

    // CHECKSTYLE_OFF: LineLength
    /**
     * Filesystem path of the directory to where the scm check-out is done. By default, scm checkout is done in build (target) directory,
     * which is deleted on every <code>mvn clean</code>. To avoid this and get better performance, configure
     * this location outside build structure and set <code>tryUpdate</code> to <code>true</code>.
     * See <a href="http://maven.apache.org/plugins/maven-scm-publish-plugin/various-tips.html#Improving_SCM_Checkout_Performance">
     * Improving SCM Checkout Performance</a> for more information.
     */
    // CHECKSTYLE_ON: LineLength
    @Parameter(
            property = "scmpublish.checkoutDirectory",
            defaultValue = "${project.build.directory}/scmpublish-checkout")
    protected File checkoutDirectory;

    /**
     * Location where the content is published inside the <code>${checkoutDirectory}</code>.
     * By default, content is copyed at the root of <code>${checkoutDirectory}</code>.
     */
    @Parameter(property = "scmpublish.subDirectory")
    protected String subDirectory;

    /**
     * If set to {@true} displays list of added, deleted, and changed files, but does not do any actual SCM operations.
     */
    @Parameter(property = "scmpublish.dryRun")
    private boolean dryRun;

    /**
     * Set this to {@code true} to skip site deployment.
     *
     * @deprecated Please use {@link #skipDeployment}.
     */
    @Deprecated
    @Parameter(defaultValue = "false")
    private boolean skipDeployement;

    /**
     * Set this to {@code true} to skip site deployment.
     */
    @Parameter(property = "scmpublish.skipDeploy", alias = "maven.site.deploy.skip", defaultValue = "false")
    private boolean skipDeployment;

    /**
     * Only executes local SCM add and delete commands, but leave the actual checkin for the user to run manually.
     */
    @Parameter(property = "scmpublish.skipCheckin")
    private boolean skipCheckin;

    /**
     * SCM log/checkin comment for this publication.
     */
    @Parameter(property = "scmpublish.checkinComment", defaultValue = "Site checkin for project ${project.name}")
    private String checkinComment;

    /**
     * Patterns to exclude from the scm tree.
     */
    @Parameter
    protected String excludes;

    /**
     * Patterns to include in the scm checkout.
     */
    @Parameter
    protected String includes;

    /**
     * List of SCM provider implementations.
     * Key is the provider type, eg. <code>cvs</code>.
     * Value is the provider implementation (the role-hint of the provider), eg. <code>cvs</code> or
     * <code>cvs_native</code>.
     * @see ScmManager.setScmProviderImplementation
     */
    @Parameter
    private Map<String, String> providerImplementations;

    /**
     * The SCM manager.
     */
    @Component
    private ScmManager scmManager;

    /**
     * Tool that gets a configured SCM repository from release configuration.
     */
    @Component
    protected ScmRepositoryConfigurator scmRepositoryConfigurator;

    /**
     * The server id specified in the {@code settings.xml}, which should be used for the authentication.
     * @see <a href="https://maven.apache.org/settings.html#servers">Settings Reference</a>
     */
    @Parameter(property = "scmpublish.serverId", defaultValue = "${project.distributionManagement.site.id}")
    private String serverId;

    /**
     * The SCM username to use.
     * This value takes precedence over the username derived from {@link #serverId}.
     * @see #serverId
     */
    @Parameter(property = "username")
    protected String username;

    /**
     * The SCM password to use.
     * This value takes precedence over the password derived from {@link #serverId}.
     * @see #serverId
     */
    @Parameter(property = "password")
    protected String password;

    /**
     * Use a local checkout instead of doing a checkout from the upstream repository.
     * <b>WARNING</b>: This will only work with distributed SCMs which support the file:// protocol.
     * TODO: we should think about having the defaults for the various SCM providers provided via Modello!
     */
    @Parameter(property = "localCheckout", defaultValue = "false")
    protected boolean localCheckout;

    /**
     * The outputEncoding parameter of the site plugin. This plugin will corrupt your site
     * if this does not match the value used by the site plugin.
     */
    @Parameter(property = "outputEncoding", defaultValue = "${project.reporting.outputEncoding}")
    protected String siteOutputEncoding;

    /**
     * If set to {@code true} does not delete files in the SCM that are not in the site.
     */
    @Parameter(property = "scmpublish.skipDeletedFiles", defaultValue = "false")
    protected boolean skipDeletedFiles;

    /**
     * Add each directory in a separated SCM command: this can be necessary if SCM does not support
     * adding subdirectories in one command.
     */
    @Parameter(defaultValue = "false")
    protected boolean addUniqueDirectory;

    /**
     */
    @Parameter(defaultValue = "${basedir}", readonly = true)
    protected File basedir;

    /**
     */
    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    protected Settings settings;

    /**
     * Collections of path patterns specifying files/directories which should never be deleted by this goal.
     * Each pattern is either
     * <ul>
     * <li>an Ant pattern (when surrounded by {@code %ant[} and {@code ]} or not starting with {@code %regex[}), or</li>
     * <li>a full regex pattern when surrounded by  {@code %regex[} and {@code ]}.</li>
     * </ul>
     * Files/directories with a matching path will be skipped when deleting files from the SCM.
     * <p>
     * If your site has subdirectories or individual files published by an other mechanism/build you should leverage this parameter.
     * @see <a href="http://ant.apache.org/manual/dirtasks.html#patterns">Ant Patterns</a>
     * @see org.codehaus.plexus.util.MatchPatterns
     * @see #skipDeletedFiles
     */
    @Parameter
    protected String[] ignorePathsToDelete;

    /**
     * SCM branch to use. For using with <a href="https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site#publishing-from-a-branch">GitHub Pages</a>,
     * you conventionally use <code>gh-pages</code>.
     */
    @Parameter(property = "scmpublish.scm.branch")
    protected String scmBranch;

    /**
     * Configure svn automatic remote url creation.
     */
    @Parameter(property = "scmpublish.automaticRemotePathCreation", defaultValue = "true")
    protected boolean automaticRemotePathCreation;

    /**
     * File name extensions of files where line ending normalization should be applied.
     * @see #extraNormalizeExtensions
     */
    private static final String[] NORMALIZE_EXTENSIONS = {"html", "css", "js"};

    /**
     * Additional file name extensions of files where line ending normalization should be applied (will be added to the default list containing
     * <code>html</code>,<code>css</code> and <code>js</code>)
     */
    @Parameter
    protected String[] extraNormalizeExtensions;

    private Set<String> normalizeExtensions;

    protected ScmProvider scmProvider;

    protected ScmRepository scmRepository;

    protected void logInfo(String format, Object... params) {
        getLog().info(String.format(format, params));
    }

    protected void logWarn(String format, Object... params) {
        getLog().warn(String.format(format, params));
    }

    protected void logError(String format, Object... params) {
        getLog().error(String.format(format, params));
    }

    private File relativize(File base, File file) {
        return new File(base.toURI().relativize(file.toURI()).getPath());
    }

    protected boolean requireNormalizeNewlines(File f) throws IOException {
        if (normalizeExtensions == null) {
            normalizeExtensions = new HashSet<>(Arrays.asList(NORMALIZE_EXTENSIONS));
            if (extraNormalizeExtensions != null) {
                normalizeExtensions.addAll(Arrays.asList(extraNormalizeExtensions));
            }
        }

        return FilenameUtils.isExtension(f.getName(), normalizeExtensions);
    }

    private void setupScm() throws ScmRepositoryException, NoSuchScmProviderException {
        String scmUrl;
        if (localCheckout) {
            // in the release phase we have to change the checkout URL
            // to do a local checkout instead of going over the network.

            String provider = ScmUrlUtils.getProvider(pubScmUrl);
            String delimiter = ScmUrlUtils.getDelimiter(pubScmUrl);

            String providerPart = "scm:" + provider + delimiter;

            // X TODO: also check the information from releaseDescriptor.getScmRelativePathProjectDirectory()
            // X TODO: in case our toplevel git directory has no pom.
            // X TODO: fix pathname once I understand this.
            scmUrl = providerPart + "file://" + "target/localCheckout";
            logInfo("Performing a LOCAL checkout from " + scmUrl);
        }

        ReleaseDescriptorBuilder descriptorBuilder = new ReleaseDescriptorBuilder();
        descriptorBuilder.setInteractive(settings.isInteractiveMode());

        descriptorBuilder.setScmPassword(password);
        descriptorBuilder.setScmUsername(username);
        // used for lookup of credentials from settings.xml in DefaultScmRepositoryConfigurator
        descriptorBuilder.setScmId(serverId);
        descriptorBuilder.setWorkingDirectory(basedir.getAbsolutePath());
        descriptorBuilder.setLocalCheckout(localCheckout);
        descriptorBuilder.setScmSourceUrl(pubScmUrl);

        if (providerImplementations != null) {
            for (Map.Entry<String, String> providerEntry : providerImplementations.entrySet()) {
                logInfo(
                        "Changing the default '%s' provider implementation to '%s'.",
                        providerEntry.getKey(), providerEntry.getValue());
                scmManager.setScmProviderImplementation(providerEntry.getKey(), providerEntry.getValue());
            }
        }

        ReleaseDescriptor releaseDescriptor = descriptorBuilder.build();
        scmRepository = scmRepositoryConfigurator.getConfiguredRepository(releaseDescriptor, settings);

        scmProvider = scmRepositoryConfigurator.getRepositoryProvider(scmRepository);
    }

    protected void checkoutExisting() throws MojoExecutionException {

        if (scmProvider instanceof AbstractSvnScmProvider) {
            checkCreateRemoteSvnPath();
        }

        logInfo(
                MessageUtils.buffer().strong("%s") + " the pub tree from "
                        + MessageUtils.buffer().strong("%s") + " into %s",
                (tryUpdate ? "Updating" : "Checking out"),
                pubScmUrl,
                checkoutDirectory);

        if (checkoutDirectory.exists() && !tryUpdate) {

            try {
                FileUtils.deleteDirectory(checkoutDirectory);
            } catch (IOException e) {
                logError(e.getMessage());

                throw new MojoExecutionException("Unable to remove old checkout directory: " + e.getMessage(), e);
            }
        }

        boolean forceCheckout = false;

        if (!checkoutDirectory.exists()) {

            if (tryUpdate) {
                logInfo("TryUpdate is configured but no local copy currently available: forcing checkout.");
            }
            checkoutDirectory.mkdirs();
            forceCheckout = true;
        }

        try {
            ScmFileSet fileSet = new ScmFileSet(checkoutDirectory, includes, excludes);

            ScmBranch branch = (scmBranch == null) ? null : new ScmBranch(scmBranch);

            ScmResult scmResult = null;
            if (tryUpdate && !forceCheckout) {
                scmResult = scmProvider.update(scmRepository, fileSet, branch);
            } else {
                int attempt = 0;
                while (scmResult == null) {
                    try {
                        scmResult = scmProvider.checkOut(scmRepository, fileSet, branch);
                    } catch (ScmException e) {
                        // give it max 2 times to retry
                        if (attempt++ < 2) {
                            try {
                                // wait 3 seconds
                                Thread.sleep(3 * 1000);
                            } catch (InterruptedException ie) {
                                // noop
                            }
                        } else {
                            throw e;
                        }
                    }
                }
            }
            checkScmResult(scmResult, "check out from SCM");
        } catch (ScmException | IOException e) {
            logError(e.getMessage());

            throw new MojoExecutionException("An error occurred during the checkout process: " + e.getMessage(), e);
        }
    }

    private void checkCreateRemoteSvnPath() throws MojoExecutionException {
        getLog().debug("AbstractSvnScmProvider used, so we can check if remote url exists and eventually create it.");
        AbstractSvnScmProvider svnScmProvider = (AbstractSvnScmProvider) scmProvider;

        try {
            boolean remoteExists = svnScmProvider.remoteUrlExist(scmRepository.getProviderRepository(), null);

            if (remoteExists) {
                return;
            }
        } catch (ScmException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        String remoteUrl = ((SvnScmProviderRepository) scmRepository.getProviderRepository()).getUrl();

        if (!automaticRemotePathCreation) {
            // olamy: return ?? that will fail during checkout IMHO :-)
            logWarn("Remote svn url %s does not exist and automatic remote path creation disabled.", remoteUrl);
            return;
        }

        logInfo("Remote svn url %s does not exist: creating.", remoteUrl);

        File baseDir = null;
        try {

            // create a temporary directory for svnexec
            baseDir = Files.createTempDirectory("scm").toFile();

            // to prevent fileSet cannot be empty
            ScmFileSet scmFileSet = new ScmFileSet(baseDir, new File(""));

            CommandParameters commandParameters = new CommandParameters();
            commandParameters.setString(CommandParameter.SCM_MKDIR_CREATE_IN_LOCAL, Boolean.FALSE.toString());
            commandParameters.setString(CommandParameter.MESSAGE, "Automatic svn path creation: " + remoteUrl);
            svnScmProvider.mkdir(scmRepository.getProviderRepository(), scmFileSet, commandParameters);

            // new remote url so force checkout!
            if (checkoutDirectory.exists()) {
                FileUtils.deleteDirectory(checkoutDirectory);
            }
        } catch (IOException | ScmException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            if (baseDir != null) {
                try {
                    FileUtils.forceDeleteOnExit(baseDir);
                } catch (IOException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            }
        }
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipDeployment || skipDeployement) {
            getLog().info("scmpublish.skipDeploy = true: Skipping site deployment");
            return;
        }

        // setup the scm plugin with help from release plugin utilities
        try {
            setupScm();
        } catch (ScmRepositoryException | NoSuchScmProviderException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        boolean tmpCheckout = false;

        if (checkoutDirectory.getPath().contains("${project.")) {
            try {
                tmpCheckout = true;
                checkoutDirectory = Files.createTempDirectory("maven-scm-publish" + ".checkout")
                        .toFile();
            } catch (IOException ioe) {
                throw new MojoExecutionException(ioe.getMessage(), ioe);
            }
        }

        try {
            scmPublishExecute();
        } finally {
            if (tmpCheckout) {
                FileUtils.deleteQuietly(checkoutDirectory);
            }
        }
    }

    /**
     * Check-in content from scm checkout.
     *
     * @throws MojoExecutionException in case of issue
     */
    protected void checkinFiles() throws MojoExecutionException {
        if (skipCheckin) {
            return;
        }

        ScmFileSet updatedFileSet = new ScmFileSet(checkoutDirectory);
        try {
            long start = System.currentTimeMillis();

            CheckInScmResult checkinResult = checkScmResult(
                    scmProvider.checkIn(scmRepository, updatedFileSet, new ScmBranch(scmBranch), checkinComment),
                    "check-in files to SCM");

            logInfo(
                    "Checked in %d file(s) to revision %s in %s",
                    checkinResult.getCheckedInFiles().size(),
                    checkinResult.getScmRevision(),
                    DurationFormatUtils.formatPeriod(start, System.currentTimeMillis(), "H' h 'm' m 's' s'"));
        } catch (ScmException e) {
            throw new MojoExecutionException("Failed to perform SCM checkin", e);
        }
    }

    protected void deleteFiles(Collection<File> deleted) throws MojoExecutionException {
        if (skipDeletedFiles) {
            logInfo("Deleting files is skipped.");
            return;
        }
        List<File> deletedList = new ArrayList<>();
        for (File f : deleted) {
            deletedList.add(relativize(checkoutDirectory, f));
        }
        ScmFileSet deletedFileSet = new ScmFileSet(checkoutDirectory, deletedList);
        try {
            getLog().info("Deleting files: " + deletedList);

            checkScmResult(
                    scmProvider.remove(scmRepository, deletedFileSet, "Deleting obsolete site files."),
                    "delete files from SCM");
        } catch (ScmException e) {
            throw new MojoExecutionException("Failed to delete removed files to SCM", e);
        }
    }

    /**
     * Add files to scm.
     *
     * @param added files to be added
     * @throws MojoFailureException in case of issue
     * @throws MojoExecutionException in case of issue
     */
    protected void addFiles(Collection<File> added) throws MojoFailureException, MojoExecutionException {
        List<File> addedList = new ArrayList<>();
        Set<File> createdDirs = new HashSet<>();
        Set<File> dirsToAdd = new TreeSet<>();

        createdDirs.add(relativize(checkoutDirectory, checkoutDirectory));

        for (File f : added) {
            for (File dir = f.getParentFile(); !dir.equals(checkoutDirectory); dir = dir.getParentFile()) {
                File relativized = relativize(checkoutDirectory, dir);
                //  we do the best we can with the directories
                if (createdDirs.add(relativized)) {
                    dirsToAdd.add(relativized);
                } else {
                    break;
                }
            }
            addedList.add(relativize(checkoutDirectory, f));
        }

        if (addUniqueDirectory) { // add one directory at a time
            for (File relativized : dirsToAdd) {
                try {
                    ScmFileSet fileSet = new ScmFileSet(checkoutDirectory, relativized);
                    getLog().info("scm add directory: " + relativized);
                    AddScmResult addDirResult = scmProvider.add(scmRepository, fileSet, "Adding directory");
                    if (!addDirResult.isSuccess()) {
                        getLog().warn(" Error adding directory " + relativized + ": "
                                + addDirResult.getCommandOutput());
                    }
                } catch (ScmException e) {
                    //
                }
            }
        } else { // add all directories in one command
            try {
                List<File> dirs = new ArrayList<>(dirsToAdd);
                ScmFileSet fileSet = new ScmFileSet(checkoutDirectory, dirs);
                getLog().info("scm add directories: " + dirs);
                AddScmResult addDirResult = scmProvider.add(scmRepository, fileSet, "Adding directories");
                if (!addDirResult.isSuccess()) {
                    getLog().warn(" Error adding directories " + dirs + ": " + addDirResult.getCommandOutput());
                }
            } catch (ScmException e) {
                //
            }
        }

        // remove directories already added !
        addedList.removeAll(dirsToAdd);

        ScmFileSet addedFileSet = new ScmFileSet(checkoutDirectory, addedList);
        getLog().info("scm add files: " + addedList);
        try {
            CommandParameters commandParameters = new CommandParameters();
            commandParameters.setString(CommandParameter.MESSAGE, "Adding new site files.");
            commandParameters.setString(CommandParameter.FORCE_ADD, Boolean.TRUE.toString());
            checkScmResult(scmProvider.add(scmRepository, addedFileSet, commandParameters), "add new files to SCM");
        } catch (ScmException e) {
            throw new MojoExecutionException("Failed to add new files to SCM", e);
        }
    }

    private <T extends ScmResult> T checkScmResult(T result, String failure) throws MojoExecutionException {
        if (!result.isSuccess()) {
            String msg = "Failed to " + failure + ": " + result.getProviderMessage() + " " + result.getCommandOutput();
            logError(msg);
            throw new MojoExecutionException(msg);
        }
        return result;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public abstract void scmPublishExecute() throws MojoExecutionException, MojoFailureException;

    public void setPubScmUrl(String pubScmUrl) {
        // Fix required for Windows, which fit other OS as well
        if (pubScmUrl.startsWith("scm:svn:")) {
            pubScmUrl = pubScmUrl.replaceFirst("file:/[/]*", "file:///");
        }

        this.pubScmUrl = pubScmUrl;
    }
}
