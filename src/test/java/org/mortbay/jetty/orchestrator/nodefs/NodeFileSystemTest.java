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
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sshd.client.SshClient;
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
    private static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private Closer closer;

    @BeforeEach
    public void setUp()
    {
        closer = new Closer();
    }

    @AfterEach
    public void tearDown()
    {
        closer.close();
    }

    @Test
    public void testNodeIdFolder() throws Exception
    {
        new File("target/testNodeIdFolder/." + NodeFileSystemProvider.SCHEME + "/the-test/myhost/a").mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testNodeIdFolder"));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(NodeFileSystemProvider.SFTP_HOST_ENV, "localhost");
        env.put(NodeFileSystemProvider.SFTP_PORT_ENV, testSshServer.getPort());
        env.put(NodeFileSystemProvider.SFTP_USERNAME_ENV, System.getProperty("user.name"));
        env.put(SshClient.class.getName(), sshClient);
        NodeFileSystem fileSystem = closer.register((NodeFileSystem)FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost!/." + NodeFileSystemProvider.SCHEME + "/the-test/myhost"), env));

        Files.newDirectoryStream(fileSystem.getPath(".")).forEach((s) -> System.out.println(s));
        Files.newDirectoryStream(fileSystem.getPath(".jco")).forEach((s) -> System.out.println(s));

        DirectoryStream<Path> paths = Files.newDirectoryStream(fileSystem.getPath("."));
        Iterator<Path> iterator = paths.iterator();
        assertThat(iterator.hasNext(), is(true));
        assertThat(iterator.next().toString(), is("a"));
        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    public void testCopy() throws Exception
    {
        new File("target/testCopy/." + NodeFileSystemProvider.SCHEME + "/the-test/myhost/a").mkdirs();

        for (String classpathEntry : System.getProperty("java.class.path").split(File.pathSeparator))
        {
            File cpFile = new File(classpathEntry);
            if (cpFile.isDirectory())
                continue;
            copyFile(cpFile.toPath(), Paths.get("target/testCopy/.jco/the-test/myhost", cpFile.getName()));
        }

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testCopy"));
        SshClient sshClient = closer.register(SshClient.setUpDefaultClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(NodeFileSystemProvider.SFTP_HOST_ENV, "localhost");
        env.put(NodeFileSystemProvider.SFTP_PORT_ENV, testSshServer.getPort());
        env.put(NodeFileSystemProvider.SFTP_USERNAME_ENV, System.getProperty("user.name"));
        env.put(SshClient.class.getName(), sshClient);
        NodeFileSystem fileSystem = closer.register((NodeFileSystem)FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost!/." + NodeFileSystemProvider.SCHEME + "/the-test/myhost"), env));

        Path targetPath = Paths.get("target/testCopy-target/");
        Files.createDirectories(targetPath);
        Path sourcePath = fileSystem.getPath(".");

        copyDir(sourcePath, targetPath);
    }

    public static void copyDir(Path srcDir, Path destDir) throws IOException
    {
        if (!Files.isDirectory(Objects.requireNonNull(srcDir)))
            throw new IllegalArgumentException("Source is not a directory: " + srcDir);
        Objects.requireNonNull(destDir);
        if (Files.exists(destDir) && !Files.isDirectory(destDir))
            throw new IllegalArgumentException("Destination is not a directory: " + destDir);
        else if (!Files.exists(destDir))
            Files.createDirectory(destDir); // only attempt top create 1 level of directory (parent must exist)

        try (Stream<Path> sourceStream = Files.walk(srcDir))
        {
            Iterator<Path> iterFiles = sourceStream
                .filter(Files::isRegularFile)
                .iterator();
            while (iterFiles.hasNext())
            {
                Path sourceFile = iterFiles.next();
                Path relative = srcDir.relativize(sourceFile);
                Path destFile = resolvePath(destDir, relative);
                if (!Files.exists(destFile.getParent()))
                    Files.createDirectories(destFile.getParent());
                copyFile(sourceFile, destFile);
            }
        }
    }

    public static void copyFile(Path srcFile, Path destFile) throws IOException
    {
        if (!Files.isRegularFile(Objects.requireNonNull(srcFile)))
            throw new IllegalArgumentException("Source is not a file: " + srcFile);
        Objects.requireNonNull(destFile);

        try (OutputStream out = Files.newOutputStream(destFile,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            Files.copy(srcFile, out);
        }
    }
    
    public static Path resolvePath(Path basePath, Path relative)
    {
        if (relative.isAbsolute())
            throw new IllegalArgumentException("Relative path cannot be absolute");

        if (basePath.getFileSystem().equals(relative.getFileSystem()))
        {
            return basePath.resolve(relative);
        }
        else
        {
            for (Path segment : relative)
                basePath = basePath.resolve(segment.toString());
            return basePath;
        }
    }


    @Test
    public void testHomeFolderIsDefault() throws Exception
    {
        new File("target/testHomeFolderIsDefault/." + NodeFileSystemProvider.SCHEME + "/the-test/myhost").mkdirs();

        TestSshServer testSshServer = closer.register(new TestSshServer("target/testHomeFolderIsDefault"));
        SshClient sshClient = closer.register(new SshClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(SshClient.class.getName(), sshClient);
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost"), env));

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
        SshClient sshClient = closer.register(new SshClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(SshClient.class.getName(), sshClient);
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost"), env));

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
        SshClient sshClient = closer.register(new SshClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(SshClient.class.getName(), sshClient);
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost"), env));

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
        SshClient sshClient = closer.register(new SshClient());
        sshClient.start();
        closer.register(sshClient.connect(null, "localhost", testSshServer.getPort())
            .verify(30, TimeUnit.SECONDS)
            .getSession());

        HashMap<String, Object> env = new HashMap<>();
        env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, IS_WINDOWS);
        env.put(SshClient.class.getName(), sshClient);
        FileSystem fileSystem = closer.register(FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":the-test/myhost"), env));

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
