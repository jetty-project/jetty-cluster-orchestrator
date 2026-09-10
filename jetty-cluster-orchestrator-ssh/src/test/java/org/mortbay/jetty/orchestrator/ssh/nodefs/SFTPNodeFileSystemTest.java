//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.mortbay.jetty.orchestrator.ssh.nodefs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import sshd.TestSshServer;
import utils.Closer;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SFTPNodeFileSystemTest {
    private Closer closer;

    @BeforeEach
    public void setUp() {
        closer = new Closer();
    }

    @AfterEach
    public void tearDown() throws Exception {
        closer.close();
    }

    @Test
    public void testNodeIdFolder() throws Exception {
        Files.createDirectories(
                Paths.get("target/testNodeIdFolder/." + NodeFileSystemProvider.PREFIX + "/the-test/myhost/a"));

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testNodeIdFolder"));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        ClientSession session = closer.register(sshClient
                .connect("username", "localhost", testSshServer.getPort())
                .verify()
                .getSession());
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(""));
        session.auth().verify();

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(
                SftpClient.class.getName(),
                closer.register(SftpClientFactory.instance().createSftpClient(session)));
        SFTPNodeFileSystem fileSystem = closer.register((SFTPNodeFileSystem) FileSystems.newFileSystem(
                URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost!/." + NodeFileSystemProvider.PREFIX
                        + "/the-test/myhost"),
                env));

        try (DirectoryStream<Path> paths = Files.newDirectoryStream(fileSystem.getPath("."))) {
            Iterator<Path> iterator = paths.iterator();
            assertThat(iterator.hasNext(), is(true));
            assertThat(iterator.next().toString(), is("a"));
            assertThat(iterator.hasNext(), is(false));
        }
    }

    @Test
    public void testHomeFolderIsDefault() throws Exception {
        Files.createDirectories(
                Paths.get("target/testHomeFolderIsDefault/." + NodeFileSystemProvider.PREFIX + "/the-test/myhost"));

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testHomeFolderIsDefault"));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        ClientSession session = closer.register(sshClient
                .connect("username", "localhost", testSshServer.getPort())
                .verify()
                .getSession());
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(""));
        session.auth().verify();

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(
                SftpClient.class.getName(),
                closer.register(SftpClientFactory.instance().createSftpClient(session)));
        FileSystem fileSystem = closer.register(
                FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        try (DirectoryStream<Path> paths = Files.newDirectoryStream(fileSystem.getPath("."))) {
            Iterator<Path> iterator = paths.iterator();
            assertThat(iterator.hasNext(), is(true));
            assertThat(iterator.next().toString(), is(".jco"));
            assertThat(iterator.hasNext(), is(false));
        }
    }

    @Test
    public void testAbsolutePath() throws Exception {
        Files.createDirectories(Paths.get("target/testAbsolutePath"));

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testAbsolutePath"));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        ClientSession session = closer.register(sshClient
                .connect("username", "localhost", testSshServer.getPort())
                .verify()
                .getSession());
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(""));
        session.auth().verify();

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(
                SftpClient.class.getName(),
                closer.register(SftpClientFactory.instance().createSftpClient(session)));
        FileSystem fileSystem = closer.register(
                FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
            long pathCount = StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(directoryStream.iterator(), Spliterator.ORDERED), false)
                    .count();
            assertThat(pathCount, greaterThan(0L));
        }
    }

    @Test
    public void testJvmFilenameSupplierFound() throws Exception {
        Path home = Paths.get("target/testJvmFilenameSupplierFound");
        Path folder = home.resolve("storage/jdk11/the-jdk11-folder/bin");
        Files.createDirectories(folder);
        Path javaFile = folder.resolve("java");
        Files.newOutputStream(javaFile).close();
        makeExecutable(javaFile);

        TestSshServer testSshServer = closer.register(new TestSshServer(home.toString()));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        ClientSession session = closer.register(sshClient
                .connect("username", "localhost", testSshServer.getPort())
                .verify()
                .getSession());
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(""));
        session.auth().verify();

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(
                SftpClient.class.getName(),
                closer.register(SftpClientFactory.instance().createSftpClient(session)));
        FileSystem fileSystem = closer.register(
                FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        Jvm jvm = new Jvm((fs, h) -> {
            try (Stream<Path> stream = Files.walk(fs.getPath("storage"), 2)) {
                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                        .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                        .findAny()
                        .orElseThrow(() -> new RuntimeException("jdk not found"));
            } catch (IOException e) {
                throw new RuntimeException("jdk not found", e);
            }
        });
        String executable = jvm.executable(fileSystem, "myhost");
        assertThat(executable, endsWith("bin/java"));
    }

    @Test
    public void testJvmFilenameSupplierNotFound() throws Exception {
        Path home = Paths.get("target/testJvmFilenameSupplierNotFound");
        Path folder = home.resolve("storage");
        Files.createDirectories(folder);

        TestSshServer testSshServer = closer.register(new TestSshServer(home.toString()));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        ClientSession session = closer.register(sshClient
                .connect("username", "localhost", testSshServer.getPort())
                .verify()
                .getSession());
        session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(""));
        session.auth().verify();

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(
                SftpClient.class.getName(),
                closer.register(SftpClientFactory.instance().createSftpClient(session)));
        FileSystem fileSystem = closer.register(
                FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        assertThrows(
                NoFileException.class,
                () -> new Jvm((fs, h) -> {
                            try (Stream<Path> stream = Files.walk(fs.getPath("storage"), 2)) {
                                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                                        .map(path -> path.resolve("bin/java")
                                                .toAbsolutePath()
                                                .toString())
                                        .findAny()
                                        .orElseThrow(NoFileException::new);
                            } catch (IOException e) {
                                throw new NoDirException(e);
                            }
                        })
                        .executable(fileSystem, "myhost"));

        assertThrows(
                NoDirException.class,
                () -> new Jvm((fs, h) -> {
                            try (Stream<Path> stream = Files.walk(fs.getPath("does-not-exist"), 2)) {
                                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                                        .map(path -> path.resolve("bin/java")
                                                .toAbsolutePath()
                                                .toString())
                                        .findAny()
                                        .orElseThrow(NoFileException::new);
                            } catch (IOException e) {
                                throw new NoDirException(e);
                            }
                        })
                        .executable(fileSystem, "myhost"));
    }

    @Test
    public void testJvmFilenameSupplierLocalhostFound() throws Exception {
        Path home = Paths.get("target/testJvmFilenameSupplierLocalhostFound");
        Path folder = home.resolve("storage/jdk11/the-jdk11-folder/bin");
        Files.createDirectories(folder);
        Path javaFile = folder.resolve("java");
        Files.newOutputStream(javaFile).close();
        makeExecutable(javaFile);

        Jvm jvm = new Jvm((fs, h) -> {
            try (Stream<Path> stream = Files.walk(fs.getPath(home.toString()).resolve("storage"), 2)) {
                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                        .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                        .findAny()
                        .orElseThrow(() -> new RuntimeException("jdk not found"));
            } catch (IOException e) {
                throw new RuntimeException("jdk not found", e);
            }
        });
        String executable = jvm.executable(FileSystems.getDefault(), "myhost");
        assertThat(executable, endsWith("bin/java"));
    }

    @Test
    public void testJvmFilenameSupplierLocalhostNotFound() throws IOException {
        Path home = Paths.get("target/testJvmFilenameSupplierLocalhostNotFound");
        Path folder = home.resolve("storage");
        Files.createDirectories(folder);

        assertThrows(
                NoFileException.class,
                () -> new Jvm((fs, h) -> {
                            try (Stream<Path> stream =
                                    Files.walk(fs.getPath(home.toString()).resolve("storage"), 2)) {
                                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                                        .map(path -> path.resolve("bin/java")
                                                .toAbsolutePath()
                                                .toString())
                                        .findAny()
                                        .orElseThrow(NoFileException::new);
                            } catch (IOException e) {
                                throw new NoDirException(e);
                            }
                        })
                        .executable(FileSystems.getDefault(), "myhost"));

        assertThrows(
                NoDirException.class,
                () -> new Jvm((fs, h) -> {
                            try (Stream<Path> stream =
                                    Files.walk(fs.getPath(home.toString()).resolve("does-not-exist"), 2)) {
                                return stream.filter(path -> Files.isExecutable(path.resolve("bin/java")))
                                        .map(path -> path.resolve("bin/java")
                                                .toAbsolutePath()
                                                .toString())
                                        .findAny()
                                        .orElseThrow(NoFileException::new);
                            } catch (IOException e) {
                                throw new NoDirException(e);
                            }
                        })
                        .executable(FileSystems.getDefault(), "myhost"));
    }

    private static void makeExecutable(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } else {
            // No portable NIO equivalent on non-POSIX filesystems.
            path.toFile().setExecutable(true);
        }
    }

    private static class NoFileException extends RuntimeException {}

    private static class NoDirException extends RuntimeException {
        public NoDirException(Throwable t) {
            super(t);
        }
    }
}
