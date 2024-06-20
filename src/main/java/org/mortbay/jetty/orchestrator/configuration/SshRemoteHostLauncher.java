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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.PortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.StreamCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SshRemoteHostLauncher implements HostLauncher, JvmDependent
{
    private static final Logger LOG = LoggerFactory.getLogger(SshRemoteHostLauncher.class);
    private static final List<String> COMMON_WIN_UNAMES = Arrays.asList("Windows", "CYGWIN", "MINGW", "MSYS", "UWIN");

    private final Map<String, RemoteNodeHolder> nodes = new ConcurrentHashMap<>();
    private final String username;
    private final char[] password;
    private final int port;
    private Jvm jvm;

    public SshRemoteHostLauncher()
    {
        this(System.getProperty("user.name"), null, 22);
    }

    public SshRemoteHostLauncher(String username)
    {
        this(username, null, 22);
    }

    public SshRemoteHostLauncher(int port)
    {
        this(System.getProperty("user.home"), null, port);
    }

    public SshRemoteHostLauncher(String username, char[] password, int port)
    {
        this.username = username;
        this.password = password;
        this.port = port;
    }

    @Override
    public void close()
    {
        nodes.values().forEach(IOUtil::close);
        nodes.clear();
    }

    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public SshRemoteHostLauncher jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }

    @Override
    public String launch(GlobalNodeId globalNodeId, String connectString) throws Exception
    {
        long start = System.nanoTime();
        GlobalNodeId nodeId = globalNodeId.getHostGlobalId();
        LOG.debug("start launch of node: {}", nodeId.getHostname());
        if (!nodeId.equals(globalNodeId))
            throw new IllegalArgumentException("node id is not the one of a host node");
        if (nodes.putIfAbsent(nodeId.getHostname(), RemoteNodeHolder.NULL) != null)
            throw new IllegalArgumentException("ssh launcher already launched node on host " + nodeId.getHostname());

        SshClient sshClient = SshClient.setUpDefaultClient();
        sshClient.setForwardingFilter(new AcceptAllForwardingFilter()); // must be set, otherwise port forwarding does not work
        sshClient.start();
        FileSystem fileSystem = null;
        PortForwardingTracker forwarding = null;
        ClientChannel clientChannel = null;
        ClientSession session = null;
        try
        {
            session = sshClient.connect(username, nodeId.getHostname(), port)
                .verify(30, TimeUnit.SECONDS)
                .getSession();

            if (LOG.isDebugEnabled())
                LOG.debug("ssh to {} with username {} and password {}", nodeId.getHostname(), username, password == null ? null : "'" + new String(password) + "'");

            if (password != null && password.length > 0)
                session.addPasswordIdentity(new String(password)); // pw auth

            session.auth().verify(30, TimeUnit.SECONDS);

            // detect windows
            boolean windows = isWindows(session);

            // do remote port forwarding
            int zkPort = Integer.parseInt(connectString.split(":")[1]);
            forwarding = session.createRemotePortForwardingTracker(
                new SshdSocketAddress("localhost", 0), // remote port, dynamically choose one
                new SshdSocketAddress("localhost", zkPort)
            );
            String remoteConnectString = forwarding.getBoundAddress().toString(); // read the dynamically chosen port

            // create remote filesystem
            HashMap<String, Object> env = new HashMap<>();
            env.put(SshClient.class.getName(), sshClient);
            env.put(NodeFileSystemProvider.SFTP_HOST_ENV, nodeId.getHostname());
            env.put(NodeFileSystemProvider.SFTP_PORT_ENV, port);
            env.put(NodeFileSystemProvider.SFTP_USERNAME_ENV, username);
            env.put(NodeFileSystemProvider.SFTP_PASSWORD_ENV, password);
            env.put(NodeFileSystemProvider.IS_WINDOWS_ENV, windows);
            fileSystem = FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.SCHEME + ":" + nodeId.getHostId()), env);

            // upload classpath
            List<String> remoteClasspathEntries = new ArrayList<>();
            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            String delimiter = windows ? "\\" : "/";
            try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session))
            {
                for (String classpathEntry : classpathEntries)
                {
                    File cpFile = new File(classpathEntry);
                    String cpFileName = cpFile.getName();
                    if (!cpFileName.toLowerCase(Locale.ROOT).endsWith(".jar"))
                        remoteClasspathEntries.add("." + NodeFileSystemProvider.SCHEME + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + cpFileName);
                    if (cpFile.isDirectory())
                        copyDir(sftpClient, nodeId.getHostId(), cpFile, 1);
                    else
                        copyFile(sftpClient, nodeId.getHostId(), cpFileName, cpFile);
                }
            }
            remoteClasspathEntries.add("." + NodeFileSystemProvider.SCHEME + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + "*");

            // spawn remote node jvm
            String cmdLine = String.join(" ", buildCommandLine(fileSystem, jvm, remoteClasspathEntries, windows ? ";" : ":", nodeId.getHostId(), nodeId.getHostname(), remoteConnectString));

            LOG.info("spawning node command '{}'...", cmdLine);
            clientChannel = session.createExecChannel(cmdLine);
            clientChannel.setRedirectErrorStream(true);
            clientChannel.open().verify(30, TimeUnit.SECONDS);

            new StreamCopier(clientChannel.getInvertedOut(), System.out, true).spawnDaemon(nodeId.getHostname() + "-stdout");

            RemoteNodeHolder remoteNodeHolder = new RemoteNodeHolder(nodeId, fileSystem, sshClient, forwarding, session, clientChannel);
            nodes.put(nodeId.getHostname(), remoteNodeHolder);
            return remoteConnectString;
        }
        catch (Exception e)
        {
            IOUtil.close(fileSystem, clientChannel, session, forwarding, sshClient);
            throw new Exception("Error launching host '" + nodeId.getHostname() + "'", e);
        }
        finally
        {
            if (LOG.isDebugEnabled())
                LOG.debug("time to start host {}: {}ms", nodeId.getHostname(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static boolean isWindows(ClientSession session) throws IOException
    {
        String output;
        Integer exitStatus;
        try (ChannelExec channel = session.createExecChannel("uname -s"))
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            channel.setOut(baos);
            channel.setRedirectErrorStream(true);
            channel.open().verify(30, TimeUnit.SECONDS);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            exitStatus = channel.getExitStatus();
            output = baos.toString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }

        if (exitStatus == null)
            throw new IOException("Executing 'uname' command did not provide an exit status");

        // Cannot run "uname -s"? Assume windows.
        if (exitStatus != 0)
            return true;
        // Outputs a well-known windows uname? Assume windows.
        for (String winUname : COMMON_WIN_UNAMES)
            if (output.contains(winUname.toLowerCase(Locale.ROOT)))
                return true;
        // Assume *nix.
        return false;
    }

    private static List<String> buildCommandLine(FileSystem fileSystem, Jvm jvm, List<String> remoteClasspathEntries, String delimiter, String nodeId, String hostname, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("\"" + jvm.executable(fileSystem, hostname) + "\"");
        for (String opt : filterOutEmptyStrings(jvm.getOpts()))
            cmdLine.add("\"" + opt + "\"");
        cmdLine.add("-classpath");
        cmdLine.add("\"" + String.join(delimiter, remoteClasspathEntries) + "\"");
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add("\"" + nodeId + "\"");
        cmdLine.add("\"" + connectString + "\"");
        return cmdLine;
    }

    private static List<String> filterOutEmptyStrings(List<String> opts)
    {
        return opts.stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    private static void copyFile(SftpClient sftpClient, String hostId, String filename, File localSourceFile) throws Exception
    {
        String destFilename = "." + NodeFileSystemProvider.SCHEME + "/" + hostId + "/" + NodeProcess.CLASSPATH_FOLDER_NAME + "/" + filename;

        try (OutputStream os = sftpClient.write(destFilename);
             FileInputStream is = new FileInputStream(localSourceFile))
        {
            IOUtil.copy(is, os);
        }
    }

    private static void copyDir(SftpClient sftpClient, String hostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (file.isDirectory())
            {
                try
                {
                    sftpClient.lstat(file.getName());
                }
                catch (IOException e)
                {
                    sftpClient.mkdir(file.getName());
                }
                copyDir(sftpClient, hostId, file, depth + 1);
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
                copyFile(sftpClient, hostId, filename, file);
            }
        }
    }

    private static class RemoteNodeHolder implements AutoCloseable {
        private static final RemoteNodeHolder NULL = new RemoteNodeHolder(null, null, null, null, null, null);

        private final GlobalNodeId nodeId;
        private final FileSystem fileSystem;
        private final SshClient sshClient;
        private final AutoCloseable forwarding;
        private final ClientSession session;
        private final ClientChannel command;

        private RemoteNodeHolder(GlobalNodeId nodeId, FileSystem fileSystem, SshClient sshClient, AutoCloseable forwarding, ClientSession session, ClientChannel command) {
            this.nodeId = nodeId;
            this.fileSystem = fileSystem;
            this.sshClient = sshClient;
            this.forwarding = forwarding;
            this.session = session;
            this.command = command;
        }

        @Override
        public void close() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("closing remote node holder of node id {}", nodeId);
            IOUtil.close(fileSystem);

            // 0x03 is the character for CTRL-C -> send it to the remote PTY
            command.getInvertedIn().write(0x03);
            try
            {
                command.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            }
            catch (Exception e)
            {
                // timeout? error? too late, try to kill the process
                command.close(true);
            }
            IOUtil.close(command);
            IOUtil.close(session);
            if (!LocalHostLauncher.skipDiskCleanup())
            {
                try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session))
                {
                    deltree(sftpClient, "." + NodeFileSystemProvider.SCHEME + "/" + nodeId.getClusterId());
                }
            }
            IOUtil.close(forwarding);
            IOUtil.close(sshClient);
        }

        private static void deltree(SftpClient sftpClient, String path) throws IOException
        {
            Iterable<SftpClient.DirEntry> ls = sftpClient.readDir(path);
            for (SftpClient.DirEntry l : ls)
            {
                if (l.getAttributes().isDirectory())
                    deltree(sftpClient, l.getLongFilename());
                else
                    sftpClient.remove(l.getFilename());
            }
            sftpClient.rmdir(path);
        }
    }
}
