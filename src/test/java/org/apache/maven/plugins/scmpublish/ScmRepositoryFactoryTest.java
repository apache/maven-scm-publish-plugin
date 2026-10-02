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

import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.ScmProviderRepositoryWithHost;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsProblem;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers each branch of {@link ScmRepositoryFactory}, which replaces
 * {@code DefaultScmRepositoryConfigurator} of maven-release-manager.
 */
class ScmRepositoryFactoryTest {

    static class HostRepository extends ScmProviderRepositoryWithHost {
        HostRepository(String host, int port) {
            setHost(host);
            setPort(port);
        }
    }

    static class PlainRepository extends ScmProviderRepository {}

    static class RecordingLog extends SystemStreamLog {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void debug(CharSequence content) {}

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warnings.add(content.toString());
        }
    }

    /** Decrypts {@code {enc:x}} to {@code x}, fails for {@code {bad}}. */
    static class FakeDecrypter implements SettingsDecrypter {
        final List<String> decrypted = new ArrayList<>();

        @Override
        public SettingsDecryptionResult decrypt(SettingsDecryptionRequest request) {
            List<Server> servers = new ArrayList<>();
            List<SettingsProblem> problems = new ArrayList<>();
            for (Server in : request.getServers()) {
                Server server = in.clone();
                servers.add(server);
                server.setPassword(apply(server.getPassword(), server, problems));
                server.setPassphrase(apply(server.getPassphrase(), server, problems));
            }
            return new SettingsDecryptionResult() {
                @Override
                public Server getServer() {
                    return servers.get(0);
                }

                @Override
                public List<Server> getServers() {
                    return servers;
                }

                @Override
                public Proxy getProxy() {
                    return null;
                }

                @Override
                public List<Proxy> getProxies() {
                    return Collections.emptyList();
                }

                @Override
                public List<SettingsProblem> getProblems() {
                    return problems;
                }
            };
        }

        private String apply(String value, Server server, List<SettingsProblem> problems) {
            if (value == null) {
                return null;
            }
            decrypted.add(value);
            if (value.equals("{bad}")) {
                problems.add(new DefaultSettingsProblem(
                        "Failed to decrypt for server " + server.getId(),
                        SettingsProblem.Severity.ERROR,
                        "server: " + server.getId(),
                        -1,
                        -1,
                        new Exception("boom")));
                return value;
            }
            if (value.startsWith("{enc:")) {
                return value.substring(5, value.length() - 1);
            }
            return value;
        }
    }

    private ScmProviderRepository providerRepository;
    private final List<Boolean> interactiveCalls = new ArrayList<>();
    private RecordingLog log;
    private FakeDecrypter decrypter;
    private Settings settings;
    private ScmRepositoryFactory factory;

    @BeforeEach
    void setUp() {
        log = new RecordingLog();
        decrypter = new FakeDecrypter();
        settings = new Settings();
        providerRepository = new HostRepository("example.org", 0);
        factory = newFactory();
    }

    private ScmRepositoryFactory newFactory() {
        ScmProvider provider = (ScmProvider) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ScmProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setInteractive")) {
                        interactiveCalls.add((Boolean) args[0]);
                    }
                    return null;
                });
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "makeScmRepository":
                    return new ScmRepository("test", providerRepository);
                case "getProviderByRepository":
                    return provider;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        ScmManager scmManager = (ScmManager) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ScmManager.class}, handler);
        return new ScmRepositoryFactory(log, scmManager, decrypter);
    }

    private Server addServer(String id, String user, String password, String privateKey, String passphrase) {
        Server server = new Server();
        server.setId(id);
        server.setUsername(user);
        server.setPassword(password);
        server.setPrivateKey(privateKey);
        server.setPassphrase(passphrase);
        settings.addServer(server);
        return server;
    }

    private ScmRepository configure(String serverId, String user, String password, boolean push, boolean interactive)
            throws Exception {
        return factory.getConfiguredRepository("scm:test:x", serverId, user, password, push, interactive, settings);
    }

    private ScmProviderRepositoryWithHost host() {
        return (ScmProviderRepositoryWithHost) providerRepository;
    }

    @Test
    @SuppressWarnings("deprecation")
    void noServerNoCredentials() throws Exception {
        configure("srv", null, null, true, true);

        assertFalse(providerRepository.isPersistCheckout());
        assertTrue(providerRepository.isPushChanges());
        assertNull(providerRepository.getUser());
        assertNull(providerRepository.getPassword());
        assertNull(host().getPrivateKey());
        assertNull(host().getPassphrase());
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("No server with id 'srv' found"));
        assertTrue(decrypter.decrypted.isEmpty());
    }

    @Test
    void pushChangesFalseIsPropagated() throws Exception {
        configure(null, null, null, false, true);
        assertFalse(providerRepository.isPushChanges());
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void explicitCredentialsWithoutSettings() throws Exception {
        ScmRepository repository =
                factory.getConfiguredRepository("scm:test:x", "srv", "me", "secret", true, true, null);

        assertEquals("me", repository.getProviderRepository().getUser());
        assertEquals("secret", repository.getProviderRepository().getPassword());
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void credentialsFromServerById() throws Exception {
        addServer("srv", "suser", "spass", "/key", "sphrase");

        configure("srv", null, null, true, true);

        assertEquals("suser", providerRepository.getUser());
        assertEquals("spass", providerRepository.getPassword());
        assertEquals("/key", host().getPrivateKey());
        assertEquals("sphrase", host().getPassphrase());
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void explicitCredentialsWinOverServer() throws Exception {
        addServer("srv", "suser", "{enc:spass}", "/key", "{enc:sphrase}");

        configure("srv", "me", "secret", true, true);

        assertEquals("me", providerRepository.getUser());
        assertEquals("secret", providerRepository.getPassword());
        // only the private key and passphrase are not overridable by parameters
        assertEquals("/key", host().getPrivateKey());
        assertEquals("sphrase", host().getPassphrase());
        assertEquals(Collections.singletonList("{enc:sphrase}"), decrypter.decrypted);
    }

    @Test
    void explicitUsernameOnlyTakesPasswordFromServer() throws Exception {
        addServer("srv", "suser", "spass", null, null);

        configure("srv", "me", null, true, true);

        assertEquals("me", providerRepository.getUser());
        assertEquals("spass", providerRepository.getPassword());
    }

    @Test
    void encryptedPasswordAndPassphraseAreDecrypted() throws Exception {
        addServer("srv", "suser", "{enc:spass}", null, "{enc:sphrase}");

        configure("srv", null, null, true, true);

        assertEquals("spass", providerRepository.getPassword());
        assertEquals("sphrase", host().getPassphrase());
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void undecryptablePasswordIsUsedAsIsWithWarning() throws Exception {
        addServer("srv", "suser", "{bad}", null, "{enc:sphrase}");

        configure("srv", null, null, true, true);

        assertEquals("{bad}", providerRepository.getPassword());
        assertEquals("sphrase", host().getPassphrase());
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("Failed to decrypt password/passphrase for server with id 'srv'"));
        assertTrue(log.warnings.get(0).contains("using auth token as is"));
    }

    @Test
    void undecryptablePassphraseIsUsedAsIsWithWarning() throws Exception {
        addServer("srv", "suser", null, null, "{bad}");

        configure("srv", null, null, true, true);

        assertEquals("{bad}", host().getPassphrase());
        assertEquals(1, log.warnings.size());
    }

    @Test
    void serverFoundByHost() throws Exception {
        addServer("example.org", "huser", "hpass", null, null);

        configure(null, null, null, true, true);

        assertEquals("huser", providerRepository.getUser());
        assertEquals("hpass", providerRepository.getPassword());
        assertTrue(log.warnings.isEmpty());
    }

    @Test
    void serverFoundByHostAndPort() throws Exception {
        providerRepository = new HostRepository("example.org", 8443);
        factory = newFactory();
        addServer("example.org", "wrong", "wrong", null, null);
        addServer("example.org:8443", "huser", "hpass", null, null);

        configure(null, null, null, true, true);

        assertEquals("huser", providerRepository.getUser());
        assertEquals("hpass", providerRepository.getPassword());
    }

    @Test
    void hostPortWithoutMatchingServerDoesNotFallBackToBareHost() throws Exception {
        providerRepository = new HostRepository("example.org", 8443);
        factory = newFactory();
        addServer("example.org", "wrong", "wrong", null, null);

        configure(null, null, null, true, true);

        assertNull(providerRepository.getUser());
    }

    @Test
    void unknownServerIdFallsBackToHostAfterWarning() throws Exception {
        addServer("example.org", "huser", "hpass", null, null);

        configure("missing", null, null, true, true);

        assertEquals("huser", providerRepository.getUser());
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("No server with id 'missing' found"));
    }

    @Test
    void serverByIdWinsOverHost() throws Exception {
        addServer("srv", "suser", "spass", null, null);
        addServer("example.org", "huser", "hpass", null, null);

        configure("srv", null, null, true, true);

        assertEquals("suser", providerRepository.getUser());
    }

    @Test
    void repositoryWithoutHostUsesOnlyServerId() throws Exception {
        providerRepository = new PlainRepository();
        factory = newFactory();
        addServer("srv", "suser", "{enc:spass}", "/key", "sphrase");

        configure("srv", null, null, true, true);

        assertEquals("suser", providerRepository.getUser());
        assertEquals("spass", providerRepository.getPassword());
    }

    @Test
    void repositoryWithoutHostIgnoresHostLookup() throws Exception {
        providerRepository = new PlainRepository();
        factory = newFactory();
        addServer("example.org", "huser", "hpass", null, null);

        configure(null, null, null, true, true);

        assertNull(providerRepository.getUser());
    }

    @Test
    void emptyCredentialsAreNotApplied() throws Exception {
        configure(null, "", "", true, true);

        assertNull(providerRepository.getUser());
        assertNull(providerRepository.getPassword());
    }

    @Test
    void nonInteractiveSwitchesProviderToNonInteractive() throws Exception {
        configure(null, null, null, true, false);

        assertEquals(Collections.singletonList(Boolean.FALSE), interactiveCalls);
    }

    @Test
    void interactiveLeavesProviderUntouched() throws Exception {
        configure(null, null, null, true, true);

        assertTrue(interactiveCalls.isEmpty());
    }
}
