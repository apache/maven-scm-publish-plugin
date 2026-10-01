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

import java.util.Collections;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.ScmProviderRepositoryWithHost;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

/**
 * Creates the {@link ScmRepository} to publish to and applies the credentials, taken from the mojo parameters first
 * and from the {@code settings.xml} server entry second.
 */
class ScmRepositoryFactory {

    private final Log log;

    private final ScmManager scmManager;

    private final SettingsDecrypter settingsDecrypter;

    ScmRepositoryFactory(Log log, ScmManager scmManager, SettingsDecrypter settingsDecrypter) {
        this.log = log;
        this.scmManager = scmManager;
        this.settingsDecrypter = settingsDecrypter;
    }

    /**
     * @param url         the SCM URL
     * @param serverId    the settings server id, may be {@code null}
     * @param username    explicit user name, wins over the server entry
     * @param password    explicit password, wins over the server entry
     * @param pushChanges whether the provider pushes after commit
     * @param interactive whether Maven runs in interactive mode
     * @param settings    the settings to look up the server in, may be {@code null}
     */
    ScmRepository getConfiguredRepository(
            String url,
            String serverId,
            String username,
            String password,
            boolean pushChanges,
            boolean interactive,
            Settings settings)
            throws ScmRepositoryException, NoSuchScmProviderException {
        String privateKey = null;
        String passphrase = null;

        ScmRepository repository = scmManager.makeScmRepository(url);

        ScmProviderRepository scmRepo = repository.getProviderRepository();

        // MRELEASE-76
        scmRepo.setPersistCheckout(false);
        scmRepo.setPushChanges(pushChanges);

        if (settings != null) {
            Server server = null;

            if (serverId != null) {
                server = settings.getServer(serverId);
                if (server == null) {
                    log.warn("No server with id '" + serverId + "' found in Maven settings");
                }
            }

            if (server == null && scmRepo instanceof ScmProviderRepositoryWithHost) {
                ScmProviderRepositoryWithHost repositoryWithHost = (ScmProviderRepositoryWithHost) scmRepo;
                String host = repositoryWithHost.getHost();

                int port = repositoryWithHost.getPort();

                if (port > 0) {
                    host += ":" + port;
                }

                // the server id is not the host, but without a <host> element this is the best match available
                server = settings.getServer(host);
            }

            if (server != null) {
                if (username == null && server.getUsername() != null) {
                    log.debug("Using username from server id '" + serverId + "' found in Maven settings");
                    username = server.getUsername();
                }

                if (password == null && server.getPassword() != null) {
                    log.debug("Using password from server id '" + serverId + "' found in Maven settings");
                    password = decrypt(server.getId(), server.getPassword(), false);
                }

                if (privateKey == null && server.getPrivateKey() != null) {
                    log.debug("Using private key from server id '" + serverId + "' found in Maven settings");
                    privateKey = server.getPrivateKey();
                }

                if (passphrase == null && server.getPassphrase() != null) {
                    log.debug("Using passphrase from server id '" + serverId + "' found in Maven settings");
                    passphrase = decrypt(server.getId(), server.getPassphrase(), true);
                }
            }
        }

        if (!(username == null || username.isEmpty())) {
            scmRepo.setUser(username);
        } else {
            log.debug("No explicit username configured");
        }
        if (!(password == null || password.isEmpty())) {
            scmRepo.setPassword(password);
        } else {
            log.debug("No explicit password configured");
        }

        if (scmRepo instanceof ScmProviderRepositoryWithHost) {
            ScmProviderRepositoryWithHost repositoryWithHost = (ScmProviderRepositoryWithHost) scmRepo;
            if (!(privateKey == null || privateKey.isEmpty())) {
                repositoryWithHost.setPrivateKey(privateKey);
            } else {
                log.debug("No explicit private key configured");
            }

            if (!(passphrase == null || passphrase.isEmpty())) {
                repositoryWithHost.setPassphrase(passphrase);
            } else {
                log.debug("No explicit passphrase configured");
            }
        }

        if (!interactive) {
            scmManager.getProviderByRepository(repository).setInteractive(interactive);
        }
        return repository;
    }

    /**
     * Decrypts a single settings value. Only the value in use is decrypted, so an undecryptable value that is
     * overridden by an explicit parameter produces no warning. A value that cannot be decrypted is used as is.
     */
    private String decrypt(String serverId, String value, boolean passphrase) {
        Server probe = new Server();
        probe.setId(serverId);
        if (passphrase) {
            probe.setPassphrase(value);
        } else {
            probe.setPassword(value);
        }

        DefaultSettingsDecryptionRequest request = new DefaultSettingsDecryptionRequest();
        request.setServers(Collections.singletonList(probe));
        SettingsDecryptionResult result = settingsDecrypter.decrypt(request);

        if (result.getProblems().isEmpty()) {
            Server decrypted = result.getServer();
            return passphrase ? decrypted.getPassphrase() : decrypted.getPassword();
        }

        SettingsProblem problem = result.getProblems().get(0);
        String msg = "Failed to decrypt password/passphrase for server with id '" + serverId
                + "', using auth token as is: "
                + (problem.getException() != null ? problem.getException().getMessage() : problem.getMessage());
        if (log.isDebugEnabled()) {
            log.warn(msg, problem.getException());
        } else {
            log.warn(msg);
        }
        return value;
    }
}
