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

package org.mortbay.jetty.orchestrator.nodefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import sshd.TestSshServer;
import utils.Closer;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NodeFileSystemTest
{
    private Closer closer;

    @BeforeEach
    public void setUp()
    {
        closer = new Closer();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        closer.close();
    }

    @Test
    public void testNodeIdFolder() throws Exception
    {
        new File("target/testNodeIdFolder/." + NodeFileSystemProvider.PREFIX + "/the-test/myhost/a").mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testNodeIdFolder"));
        SSHClient sshClient = closer.register(new SSHClient());
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect("localhost", testSshServer.getPort());
        sshClient.authPassword("username", new char[0]);

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        NodeFileSystem fileSystem = closer.register((NodeFileSystem)FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost!/." + NodeFileSystemProvider.PREFIX + "/the-test/myhost"), env));

        DirectoryStream<Path> paths = Files.newDirectoryStream(fileSystem.getPath("."));
        Iterator<Path> iterator = paths.iterator();
        assertThat(iterator.hasNext(), is(true));
        assertThat(iterator.next().toString(), is("a"));
        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    public void testHomeFolderIsDefault() throws Exception
    {
        new File("target/testHomeFolderIsDefault/." + NodeFileSystemProvider.PREFIX + "/the-test/myhost").mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testHomeFolderIsDefault"));
        SSHClient sshClient = closer.register(new SSHClient());
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect("localhost", testSshServer.getPort());
        sshClient.authPassword("username", new char[0]);

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        DirectoryStream<Path> paths = Files.newDirectoryStream(fileSystem.getPath("."));
        Iterator<Path> iterator = paths.iterator();
        assertThat(iterator.hasNext(), is(true));
        assertThat(iterator.next().toString(), is(".jco"));
        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    public void testAbsolutePath() throws Exception
    {
        new File("target/testAbsolutePath").mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testAbsolutePath"));
        SSHClient sshClient = closer.register(new SSHClient());
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect("localhost", testSshServer.getPort());
        sshClient.authPassword("username", new char[0]);

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        DirectoryStream<Path> directoryStream = Files.newDirectoryStream(fileSystem.getPath("/"));
        long pathCount = StreamSupport.stream(Spliterators.spliteratorUnknownSize(directoryStream.iterator(), Spliterator.ORDERED), false).count();

        assertThat(pathCount, greaterThan(0L));
    }

    @Test
    public void testJvmFilenameSupplierFound() throws Exception
    {
        File home = new File("target/testJvmFilenameSupplierFound");
        File folder = new File(home, "storage/jdk11/the-jdk11-folder/bin");
        folder.mkdirs();
        File javaFile = new File(folder, "java");
        new FileOutputStream(javaFile).close();
        javaFile.setExecutable(true);

        TestSshServer testSshServer = closer.register(new TestSshServer(home.getPath()));
        SSHClient sshClient = closer.register(new SSHClient());
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect("localhost", testSshServer.getPort());
        sshClient.authPassword("username", new char[0]);

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        Jvm jvm = new Jvm((fs, h) ->
        {
            try
            {
                return Files.walk(fs.getPath("storage"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(() -> new RuntimeException("jdk not found"));
            }
            catch (IOException e)
            {
                throw new RuntimeException("jdk not found", e);
            }
        });
        String executable = jvm.executable(fileSystem, "myhost");
        assertThat(executable, endsWith("bin/java"));
    }

    @Test
    public void testJvmFilenameSupplierNotFound() throws Exception
    {
        File home = new File("target/testJvmFilenameSupplierNotFound");
        File folder = new File(home, "storage");
        folder.mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer(home.getPath()));
        SSHClient sshClient = closer.register(new SSHClient());
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect("localhost", testSshServer.getPort());
        sshClient.authPassword("username", new char[0]);

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, false);
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":the-test/myhost"), env));

        assertThrows(NoFileException.class, () -> new Jvm((fs, h) ->
        {
            try
            {
                String storage = Files.walk(fs.getPath("storage"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(NoFileException::new);
                return storage;
            }
            catch (IOException e)
            {
                throw new NoDirException(e);
            }
        }).executable(fileSystem, "myhost"));

        assertThrows(NoDirException.class, () -> new Jvm((fs, h) ->
        {
            try
            {
                return Files.walk(fs.getPath("does-not-exist"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(NoFileException::new);
            }
            catch (IOException e)
            {
                throw new NoDirException(e);
            }
        }).executable(fileSystem, "myhost"));
    }

    @Test
    public void testJvmFilenameSupplierLocalhostFound() throws Exception
    {
        File home = new File("target/testJvmFilenameSupplierLocalhostFound");
        File folder = new File(home, "storage/jdk11/the-jdk11-folder/bin");
        folder.mkdirs();
        File javaFile = new File(folder, "java");
        new FileOutputStream(javaFile).close();
        javaFile.setExecutable(true);

        Jvm jvm = new Jvm((fs, h) ->
        {
            try
            {
                return Files.walk(fs.getPath(home.getPath()).resolve("storage"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(() -> new RuntimeException("jdk not found"));
            }
            catch (IOException e)
            {
                throw new RuntimeException("jdk not found", e);
            }
        });
        String executable = jvm.executable(FileSystems.getDefault(), "myhost");
        assertThat(executable, endsWith("bin/java"));
    }

    @Test
    public void testJvmFilenameSupplierLocalhostNotFound()
    {
        File home = new File("target/testJvmFilenameSupplierLocalhostNotFound");
        File folder = new File(home, "storage");
        folder.mkdirs();

        assertThrows(NoFileException.class, () -> new Jvm((fs, h) ->
        {
            try
            {
                return Files.walk(fs.getPath(home.getPath()).resolve("storage"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(NoFileException::new);
            }
            catch (IOException e)
            {
                throw new NoDirException(e);
            }
        }).executable(FileSystems.getDefault(), "myhost"));

        assertThrows(NoDirException.class, () -> new Jvm((fs, h) ->
        {
            try
            {
                return Files.walk(fs.getPath(home.getPath()).resolve("does-not-exist"), 2)
                    .filter(path -> Files.isExecutable(path.resolve("bin/java")))
                    .map(path -> path.resolve("bin/java").toAbsolutePath().toString())
                    .findAny()
                    .orElseThrow(NoFileException::new);
            }
            catch (IOException e)
            {
                throw new NoDirException(e);
            }
        }).executable(FileSystems.getDefault(), "myhost"));
    }

    private static class NoFileException extends RuntimeException
    {
    }

    private static class NoDirException extends RuntimeException
    {
        public NoDirException(Throwable t)
        {
            super(t);
        }
    }
}
