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

package org.mortbay.jetty.orchestrator.configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.util.IOUtil;

public class LocalHostLauncher implements HostLauncher
{
    public static final String HOSTNAME = "localhost";

    private final Lock lock = new ReentrantLock();
    private Thread thread;
    private GlobalNodeId nodeId;

    @Override
    public String launch(GlobalNodeId globalNodeId, String connectString, String... extraArgs) throws Exception
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
                File cpFile = new File(classpathEntry);
                if (cpFile.isDirectory())
                {
                    copyDir(nodeId.getHostId(), cpFile, 1);
                }
                else
                {
                    String filename = cpFile.getName();
                    try (InputStream is = new FileInputStream(cpFile))
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
    public void close() throws Exception
    {
        lock.lock();
        try
        {
            if (thread != null)
            {
                thread.interrupt();
                thread.join();
                thread = null;

                File rootPath = rootPathOf(nodeId.getHostId());
                File parentPath = rootPath.getParentFile();
                if (!skipDiskCleanup() && IOUtil.deltree(rootPath) && parentPath != null)
                {
                    String[] files = parentPath.list();
                    if (files != null && files.length == 0)
                        IOUtil.deltree(parentPath);
                }
                nodeId = null;
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    public static File rootPathOf(String hostId)
    {
        return new File(System.getProperty("user.home") + "/." + NodeFileSystemProvider.PREFIX + "/" + hostId);
    }

    private static void copyFile(String hostId, String filename, InputStream contents) throws Exception
    {
        File rootPath = rootPathOf(hostId);
        File libPath = new File(rootPath, NodeProcess.CLASSPATH_FOLDER_NAME);

        File file = new File(libPath, filename);
        file.getParentFile().mkdirs();
        try (OutputStream fos = new FileOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private static void copyDir(String hostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (file.isDirectory())
            {
                copyDir(hostId, file, depth + 1);
            }
            else
            {

                String filename = file.getName();
                File currentFile = file;
                for (int i = 0; i < depth; i++)
                {
                    currentFile = currentFile.getParentFile();
                    filename = currentFile.getName() + "/" + filename;
                }
                try (InputStream is = new FileInputStream(file))
                {
                    copyFile(hostId, filename, is);
                }
            }
        }
    }

    static boolean skipDiskCleanup()
    {
        return Boolean.getBoolean("org.mortbay.jetty.orchestrator.skipDiskCleanup");
    }
}
