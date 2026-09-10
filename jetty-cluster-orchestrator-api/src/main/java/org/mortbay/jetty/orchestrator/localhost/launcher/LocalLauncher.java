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

package org.mortbay.jetty.orchestrator.localhost.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.NodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.launcher.AbstractHostLauncher;
import org.mortbay.jetty.orchestrator.localhost.configuration.LocalNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.ZooKeeperServer;

/**
 * Runs the host node as a thread in this JVM rather than a separate process.
 * The worker nodes are still forked JVMs, and {@link NodeProcess#spawn} reads their classpath
 * from {@code ~/.jco/<hostId>/.classpath}, so the classpath is copied there all the same.
 * <p>
 * Node files stay on this machine, so
 * {@link org.mortbay.jetty.orchestrator.NodeArray#rootPathOf(String)} returns a plain
 * {@link java.nio.file.Path} instead of a {@code jco:} one, and there is no local
 * {@link org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemFactory}.
 */
public class LocalLauncher extends AbstractHostLauncher
{
    public static final String HOSTNAME = "localhost";

    private final Lock lock = new ReentrantLock();
    private Thread thread;
    private GlobalNodeId nodeId;
    private ZooKeeperServer zkServer;

    @Override
    public String initialize() throws Exception {
        zkServer = new ZooKeeperServer();
        return zkServer.getConnectString();
    }

    @Override
    protected Class<? extends NodeArrayConfiguration> configurationType()
    {
        return LocalNodeArrayConfiguration.class;
    }

    @Override
    protected String launchHost(GlobalNodeId globalNodeId, Node node, String connectString, String... extraArgs) throws Exception
    {
        lock.lock();
        try
        {
            GlobalNodeId nodeId = globalNodeId.getHostGlobalId();
            if (!nodeId.equals(globalNodeId))
                throw new IllegalArgumentException("node id is not the one of a host node");
            if (!HOSTNAME.equals(nodeId.getHostname()))
                throw new IllegalArgumentException("local launcher can only work with 'localhost' hostname");
            if (thread != null)
                throw new IllegalStateException("local launcher already spawned 'localhost' thread");
            this.nodeId = nodeId;

            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            for (String classpathEntry : classpathEntries)
            {
                Path cpPath = Paths.get(classpathEntry);
                if (Files.isDirectory(cpPath))
                {
                    copyDir(nodeId.getHostId(), cpPath, 1);
                }
                else
                {
                    String filename = cpPath.getFileName().toString();
                    try (InputStream is = Files.newInputStream(cpPath))
                    {
                        copyFile(nodeId.getHostId(), filename, is);
                    }
                }
            }

            try
            {
                this.thread = NodeProcess.spawnThread(nodeId.getHostId(), connectString, extraArgs);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            return connectString;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    protected void closeHosts()
    {
        lock.lock();
        try
        {
            if (thread != null)
            {
                thread.interrupt();
                try
                {
                    thread.join();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                thread = null;

                Path rootPath = rootPathOf(nodeId.getHostId());
                Path parentPath = rootPath.getParent();
                if (!skipDiskCleanup() && IOUtil.deltree(rootPath) && parentPath != null)
                {
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(parentPath))
                    {
                        if (!children.iterator().hasNext())
                            IOUtil.deltree(parentPath);
                    }
                    catch (IOException e)
                    {
                        // parent dir may no longer exist; nothing to clean up
                    }
                }
                nodeId = null;
            }
        }
        finally
        {
            lock.unlock();
        }
        IOUtil.close(zkServer);
    }

    public static Path rootPathOf(String hostId)
    {
        return Paths.get(System.getProperty("user.home"), "." + NodeFileSystemProvider.PREFIX, hostId);
    }

    private static void copyFile(String hostId, String filename, InputStream contents) throws Exception
    {
        Path rootPath = rootPathOf(hostId);
        Path libPath = rootPath.resolve(NodeProcess.CLASSPATH_FOLDER_NAME);

        Path file = libPath.resolve(filename);
        Files.createDirectories(file.getParent());
        try (OutputStream fos = Files.newOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private static void copyDir(String hostId, Path cpPath, int depth) throws Exception
    {
        if (!Files.isDirectory(cpPath))
            return;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(cpPath))
        {
            for (Path file : files)
            {
                if (Files.isDirectory(file))
                {
                    copyDir(hostId, file, depth + 1);
                }
                else
                {
                    String filename = file.getFileName().toString();
                    Path currentPath = file;
                    for (int i = 0; i < depth; i++)
                    {
                        currentPath = currentPath.getParent();
                        filename = currentPath.getFileName().toString() + "/" + filename;
                    }
                    try (InputStream is = Files.newInputStream(file))
                    {
                        copyFile(hostId, filename, is);
                    }
                }
            }
        }
    }

    public static boolean skipDiskCleanup()
    {
        return Boolean.getBoolean("org.mortbay.jetty.orchestrator.skipDiskCleanup");
    }
}
