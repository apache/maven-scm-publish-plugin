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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.codehaus.plexus.util.MatchPatterns;

/**
 * Publish content to an SCM (source code management) repository (like Git). By default, content is taken from default site staging directory
 * <code>${project.build.directory}/staging</code>.
 * Can also be used without a Maven project, so usable to update any SCM with any content.
 * @see <a href="https://maven.apache.org/scm/index.html">Maven SCM</a>
 */
@Mojo(name = "publish-scm", aggregator = true, requiresProject = false)
public class ScmPublishPublishScmMojo extends AbstractScmPublishMojo {
    /**
     * The path of the directory containing the content to be published.
     * The is published recursively (i.e. including subdirectories).
     */
    @Parameter(property = "scmpublish.content", defaultValue = "${project.build.directory}/staging")
    private File content;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    private List<File> deleted = new ArrayList<>();

    private List<File> added = new ArrayList<>();

    private List<File> updated = new ArrayList<>();

    private int directories = 0;
    private int files = 0;
    private long size = 0;

    /**
     * Update scm checkout directory with content.
     *
     * @param checkout        the scm checkout directory
     * @param dir             the content to put in scm (can be <code>null</code>)
     * @param doNotDeleteDirs directory names that should not be deleted from scm even if not in new content:
     *                        used for modules, which content is available only when staging
     * @throws IOException
     */
    private void update(File checkout, File dir, List<String> doNotDeleteDirs) throws IOException {
        String scmSpecificFilename = scmProvider.getScmSpecificFilename();
        String[] files = scmSpecificFilename != null
                ? checkout.list(new NotFileFilter(new NameFileFilter(scmSpecificFilename)))
                : checkout.list();

        Set<String> checkoutContent = new HashSet<>(Arrays.asList(files));
        List<String> dirContent = (dir != null) ? Arrays.asList(dir.list()) : Collections.emptyList();

        Set<String> deleted = new HashSet<>(checkoutContent);
        deleted.removeAll(dirContent);

        MatchPatterns ignoreDeleteMatchPatterns = null;
        List<String> pathsAsList = new ArrayList<>(0);
        if (ignorePathsToDelete != null && ignorePathsToDelete.length > 0) {
            ignoreDeleteMatchPatterns = MatchPatterns.from(ignorePathsToDelete);
            pathsAsList = Arrays.asList(ignorePathsToDelete);
        }

        for (String name : deleted) {
            if (ignoreDeleteMatchPatterns != null && ignoreDeleteMatchPatterns.matches(name, true)) {
                getLog().debug(name + " match one of the patterns '" + pathsAsList + "': do not add to deleted files");
                continue;
            }
            getLog().debug("file marked for deletion: " + name);
            File file = new File(checkout, name);

            if ((doNotDeleteDirs != null) && file.isDirectory() && (doNotDeleteDirs.contains(name))) {
                // ignore directory not available
                continue;
            }

            if (file.isDirectory()) {
                update(file, null, null);
            }
            this.deleted.add(file);
        }

        for (String name : dirContent) {
            File file = new File(checkout, name);
            File source = new File(dir, name);

            if (Files.isSymbolicLink(source.toPath())) {
                if (!checkoutContent.contains(name)) {
                    this.added.add(file);
                }

                // copy symbolic link (Java 7 only)
                copySymLink(source, file);
            } else if (source.isDirectory()) {
                directories++;
                if (!checkoutContent.contains(name)) {
                    this.added.add(file);
                    file.mkdir();
                }

                update(file, source, null);
            } else {
                if (checkoutContent.contains(name)) {
                    this.updated.add(file);
                } else {
                    this.added.add(file);
                }

                copyFile(source, file);
            }
        }
    }

    /**
     * Copy a symbolic link.
     *
     * @param srcFile the source file (expected to be a symbolic link)
     * @param destFile the destination file (which will be a symbolic link)
     * @throws IOException
     */
    private void copySymLink(File srcFile, File destFile) throws IOException {
        Files.copy(
                srcFile.toPath(),
                destFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Copy a file content, normalizing newlines when necessary.
     *
     * @param srcFile  the source file
     * @param destFile the destination file
     * @throws IOException
     * @see #requireNormalizeNewlines(File)
     */
    private void copyFile(File srcFile, File destFile) throws IOException {
        if (requireNormalizeNewlines(srcFile)) {
            copyAndNormalizeNewlines(srcFile, destFile);
        } else {
            FileUtils.copyFile(srcFile, destFile);
        }
        files++;
        size += destFile.length();
    }

    /**
     * Copy and normalize newlines.
     *
     * @param srcFile  the source file
     * @param destFile the destination file
     * @throws IOException
     */
    private void copyAndNormalizeNewlines(File srcFile, File destFile) throws IOException {
        BufferedReader in = null;
        PrintWriter out = null;
        try {
            in = new BufferedReader(new InputStreamReader(Files.newInputStream(srcFile.toPath()), siteOutputEncoding));
            out = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(destFile.toPath()), siteOutputEncoding));

            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (in.ready()) {
                    out.println(line);
                } else {
                    out.print(line);
                }
            }

            out.close();
            out = null;
            in.close();
            in = null;
        } finally {
            IOUtils.closeQuietly(out);
            IOUtils.closeQuietly(in);
        }
    }

    public void scmPublishExecute() throws MojoExecutionException, MojoFailureException {
        if (siteOutputEncoding == null) {
            getLog().warn("No output encoding, defaulting to UTF-8.");
            siteOutputEncoding = "utf-8";
        }

        if (!content.exists()) {
            throw new MojoExecutionException("Configured content directory does not exist: " + content);
        }

        if (!content.canRead()) {
            throw new MojoExecutionException("Can't read content directory: " + content);
        }

        checkoutExisting();

        final File updateDirectory;
        if (subDirectory == null) {
            updateDirectory = checkoutDirectory;
        } else {
            updateDirectory = new File(checkoutDirectory, subDirectory);

            // Security check for subDirectory with .. inside
            if (!updateDirectory
                    .toPath()
                    .normalize()
                    .startsWith(checkoutDirectory.toPath().normalize())) {
                logError("Try to acces outside of the checkout directory with sub-directory: %s", subDirectory);
                return;
            }

            if (!updateDirectory.exists()) {
                updateDirectory.mkdirs();
            }

            logInfo("Will copy content in sub-directory: %s", subDirectory);
        }

        try {
            logInfo("Updating checkout directory with actual content in %s", content);
            update(
                    updateDirectory,
                    content,
                    (project == null) ? null : project.getModel().getModules());
            String displaySize = org.apache.commons.io.FileUtils.byteCountToDisplaySize(size);
            logInfo(
                    "Content consists of " + MessageUtils.buffer().strong("%d directories and %d files = %s"),
                    directories,
                    files,
                    displaySize);
        } catch (IOException ioe) {
            throw new MojoExecutionException("Could not copy content to SCM checkout", ioe);
        }

        logInfo(
                "Publishing content to SCM will result in "
                        + MessageUtils.buffer().strong("%d addition(s), %d update(s), %d delete(s)"),
                added.size(),
                updated.size(),
                deleted.size());

        if (isDryRun()) {
            int pos = checkoutDirectory.getAbsolutePath().length() + 1;
            for (File addedFile : added) {
                logInfo("- addition %s", addedFile.getAbsolutePath().substring(pos));
            }
            for (File updatedFile : updated) {
                logInfo("- update   %s", updatedFile.getAbsolutePath().substring(pos));
            }
            for (File deletedFile : deleted) {
                logInfo("- delete   %s", deletedFile.getAbsolutePath().substring(pos));
            }
            return;
        }

        if (!added.isEmpty()) {
            addFiles(added);
        }

        if (!deleted.isEmpty()) {
            deleteFiles(deleted);
        }

        logInfo("Checking in SCM, starting at " + new Date() + "...");
        checkinFiles();
    }
}
