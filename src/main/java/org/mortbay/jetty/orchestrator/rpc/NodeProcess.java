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

package org.mortbay.jetty.orchestrator.rpc;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.ProcessHolder;
import org.mortbay.jetty.orchestrator.util.StreamCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeProcess implements Serializable, AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(NodeProcess.class);
    public static final String CLASSPATH_FOLDER_NAME = ".classpath";

    private final ProcessHolder processHelper;

    private NodeProcess(Process process)
    {
        this.processHelper = ProcessHolder.from(process);
    }

    public boolean isAlive() throws Exception
    {
        return processHelper.isAlive();
    }

    @Override
    public void close()
    {
        try
        {
            processHelper.destroy();
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error terminating process with PID=" + processHelper.getPid(), e);
        }
    }

    @Override
    public String toString()
    {
        return "NodeProcess{" +
            "pid=" + processHelper.getPid() +
            '}';
    }

    public static void main(String[] args) throws Exception
    {
        String nodeId = args[0];
        String connectString = args[1];

        if (LOG.isDebugEnabled())
            LOG.debug("Starting node [{}] with JVM version '{}' connecting to {}", nodeId, System.getProperty("java.version"), connectString);
        CuratorFramework curator = CuratorFrameworkFactory.newClient(connectString, new RetryNTimes(0, 0));
        curator.start();
        curator.blockUntilConnected();

        if (LOG.isDebugEnabled())
            LOG.debug("Node [{}] connected to {}", nodeId, connectString);
        RpcServer rpcServer = new RpcServer(curator, new GlobalNodeId(nodeId));

        // The Cluster sends a CheckNodeCommand every 5 seconds, if we miss too many
        // we can assume the connection is dead.
        Thread keepalive = new Thread(() ->
        {
            while (true)
            {
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    return;
                }

                long delta = System.nanoTime() - rpcServer.getLastCommandTimestamp();
                if (delta > TimeUnit.MILLISECONDS.toNanos(RpcClient.HEALTH_CHECK_DELAY_MS * 3))
                {
                    LOG.error("Node [{}] missed three consecutive health checks, assuming the cluster is dead", nodeId);
                    System.exit(1);
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("node {} healthcheck passed as it happened {} ms ago", nodeId, TimeUnit.NANOSECONDS.toMillis(delta));
            }
        });
        keepalive.setDaemon(true);
        keepalive.start();

        Thread shutdown = new Thread(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Node [{}] stopping", nodeId);
            keepalive.interrupt();
            IOUtil.close(rpcServer);
            IOUtil.close(curator);
            if (LOG.isDebugEnabled())
                LOG.debug("Node [{}] stopped", nodeId);
        });
        Runtime.getRuntime().addShutdownHook(shutdown);

        if (LOG.isDebugEnabled())
            LOG.debug("Node [{}] ready to serve", nodeId);
        try
        {
            rpcServer.run();
        }
        catch (Exception e)
        {
            LOG.error("RPC server failed on node {}; aborting", nodeId, e);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Node [{}] disconnecting from {}", nodeId, connectString);
        shutdown.run(); // do not start that thread, run its runnable on the current thread
        try
        {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
        catch (IllegalStateException e)
        {
            // Shutting down; can be safely ignored.
        }
    }

    public static NodeProcess spawn(FileSystem fileSystem, Jvm jvm, String hostId, String nodeId, String hostname, String connectString) throws IOException
    {
        File nodeRootPath = defaultRootPath(nodeId);
        IOUtil.deltree(nodeRootPath);
        nodeRootPath.mkdirs();

        List<String> cmdLine = buildCommandLine(fileSystem, jvm, defaultLibPath(hostId), nodeId, hostname, connectString);
        // Inherited IO bypasses the System.setOut/setErr mechanism, so use piping for stdout/stderr such as
        // System.setOut/setErr can redirect the output of the process.
        Process process = new ProcessBuilder(cmdLine)
            .directory(nodeRootPath)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start();
        new StreamCopier(process.getInputStream(), System.out, true).spawnDaemon(hostname + "-proc-stdout");
        new StreamCopier(process.getErrorStream(), System.err, true).spawnDaemon(hostname + "-proc-stderr");
        return new NodeProcess(process);
    }

    public static Thread spawnThread(String nodeId, String connectString)
    {
        File nodeRootPath = defaultRootPath(nodeId);
        nodeRootPath.mkdirs();

        Thread t = new Thread(() ->
        {
            try
            {
                NodeProcess.main(new String[]{nodeId, connectString});
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
        t.start();
        return t;
    }

    private static File defaultRootPath(String hostId)
    {
        return new File(System.getProperty("user.home") + "/." + NodeFileSystemProvider.SCHEME + "/" + hostId);
    }

    private static File defaultLibPath(String hostId)
    {
        File rootPath = defaultRootPath(hostId);
        return new File(rootPath, CLASSPATH_FOLDER_NAME);
    }

    private static List<String> buildCommandLine(FileSystem fileSystem, Jvm jvm, File libPath, String nodeId, String hostname, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable(fileSystem, hostname));
        cmdLine.addAll(filterOutEmptyStrings(jvm.getOpts()));
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add(nodeId);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private static List<String> filterOutEmptyStrings(List<String> opts)
    {
        return opts.stream().filter(s -> !s.trim().equals("")).collect(Collectors.toList());
    }

    private static String buildClassPath(File libPath)
    {
        File[] entries = libPath.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File entry : entries)
        {
            String path = entry.getPath();
            if (!path.endsWith(".jar") && !path.endsWith(".JAR"))
                sb.append(path).append(File.pathSeparatorChar);
        }
        sb.append(libPath.getPath()).append(File.separatorChar).append("*");
        return sb.toString();
    }
}
