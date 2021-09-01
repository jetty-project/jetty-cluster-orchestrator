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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.Channel;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.LocalSourceFile;
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

    private final Map<String, RemoteNodeHolder> nodes = new HashMap<>();
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
        long start = System.currentTimeMillis();
        GlobalNodeId nodeId = globalNodeId.getHostGlobalId();
        LOG.debug("start launch of node: {}", nodeId.getHostname());
        if (!nodeId.equals(globalNodeId))
            throw new IllegalArgumentException("node id is not the one of a host node");
        if (nodes.containsKey(nodeId.getHostname()))
            throw new IllegalArgumentException("ssh launcher already launched node on host " + nodeId.getHostname());

        SSHClient sshClient = new SSHClient();
        FileSystem fileSystem = null;
        SocketForwardingConnectListener forwardingConnectListener = null;
        AutoCloseable forwarding = null;
        Session.Command cmd = null;
        Session session = null;
        try
        {
            sshClient.addHostKeyVerifier(new PromiscuousVerifier()); // or loadKnownHosts() instead?
            sshClient.connect(nodeId.getHostname(), port);

            if (LOG.isDebugEnabled())
                LOG.debug("ssh to {} with username {} and empty password {}", nodeId.getHostname(), username, password == null);

            if (password == null)
                sshClient.authPublickey(username); // public key auth
            else
                sshClient.authPassword(username, password); // pw auth

            // detect windows
            boolean windows = isWindows(sshClient);

            // do remote port forwarding
            int zkPort = Integer.parseInt(connectString.split(":")[1]);
            forwardingConnectListener = new SocketForwardingConnectListener(nodeId.getHostname(), new InetSocketAddress("localhost", zkPort));
            RemotePortForwarder.Forward forward = sshClient.getRemotePortForwarder().bind(
                new RemotePortForwarder.Forward(0), // remote port, dynamically choose one
                forwardingConnectListener
            );
            forwarding = () -> sshClient.getRemotePortForwarder().cancel(forward);
            String remoteConnectString = "localhost:" + forward.getPort();

            HashMap<String, Object> env = new HashMap<>();
            env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
            env.put(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY, windows);
            fileSystem = FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":" + nodeId.getHostId()), env);

            List<String> remoteClasspathEntries = new ArrayList<>();
            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            String delimiter = windows ? "\\" : "/";
            try (SFTPClient sftpClient = sshClient.newStatefulSFTPClient())
            {
                for (String classpathEntry : classpathEntries)
                {
                    File cpFile = new File(classpathEntry);
                    String cpFileName = cpFile.getName();
                    if (!cpFileName.endsWith(".jar") && !cpFileName.endsWith(".JAR"))
                        remoteClasspathEntries.add("." + NodeFileSystemProvider.PREFIX + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + cpFileName);
                    if (cpFile.isDirectory())
                        copyDir(sftpClient, nodeId.getHostId(), cpFile, 1);
                    else
                        copyFile(sftpClient, nodeId.getHostId(), cpFileName, new FileSystemFile(cpFile));
                }
            }
            remoteClasspathEntries.add("." + NodeFileSystemProvider.PREFIX + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + "*");

            String cmdLine = String.join(" ", buildCommandLine(fileSystem, jvm, remoteClasspathEntries, windows ? ";" : ":", nodeId.getHostId(), nodeId.getHostname(), remoteConnectString));
            session = sshClient.startSession();
            cmd = session.exec(cmdLine);

            new StreamCopier(cmd.getInputStream(), System.out, true).spawnDaemon(nodeId.getHostname() + "-stdout");
            new StreamCopier(cmd.getErrorStream(), System.err, true).spawnDaemon(nodeId.getHostname() + "-stderr");

            RemoteNodeHolder remoteNodeHolder = new RemoteNodeHolder(nodeId, fileSystem, sshClient, forwardingConnectListener, forwarding, session, cmd);
            nodes.put(nodeId.getHostname(), remoteNodeHolder);
            return remoteConnectString;
        }
        catch (Exception e)
        {
            IOUtil.close(fileSystem, cmd, session, forwardingConnectListener, forwarding, sshClient);
            throw new Exception("Error launching host '" + nodeId.getHostname() + "'", e);
        }
        finally
        {
            LOG.info("time to start host {}: {}ms", nodeId.getHostname(), (System.currentTimeMillis()-start));
        }
    }

    private boolean isWindows(SSHClient sshClient) throws IOException
    {
        try (Session session = sshClient.startSession())
        {
            Session.Command uname = session.exec("uname -s");
            uname.join();
            InputStream is = uname.getInputStream();
            StringBuilder sb = new StringBuilder();
            while (true)
            {
                int read = is.read();
                if (read == -1)
                    break;
                sb.append((char)read);
            }
            String output = sb.toString().toLowerCase(Locale.ROOT);
            uname.close();
            Integer exitStatus = uname.getExitStatus();
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
        return opts.stream().filter(s -> !s.trim().equals("")).collect(Collectors.toList());
    }

    private void copyFile(SFTPClient sftpClient, String hostId, String filename, LocalSourceFile localSourceFile) throws Exception
    {
        String destFilename = "." + NodeFileSystemProvider.PREFIX + "/" + hostId + "/" + NodeProcess.CLASSPATH_FOLDER_NAME + "/" + filename;
        String parentFilename = destFilename.substring(0, destFilename.lastIndexOf('/'));

        sftpClient.mkdirs(parentFilename);
        sftpClient.put(localSourceFile, destFilename);
    }

    private void copyDir(SFTPClient sftpClient, String hostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (file.isDirectory())
            {
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
                copyFile(sftpClient, hostId, filename, new FileSystemFile(file));
            }
        }
    }

    private static class RemoteNodeHolder implements AutoCloseable {
        private final GlobalNodeId nodeId;
        private final FileSystem fileSystem;
        private final SSHClient sshClient;
        private final SocketForwardingConnectListener forwardingConnectListener;
        private final AutoCloseable forwarding;
        private final Session session;
        private final Session.Command command;

        private RemoteNodeHolder(GlobalNodeId nodeId, FileSystem fileSystem, SSHClient sshClient, SocketForwardingConnectListener forwardingConnectListener, AutoCloseable forwarding, Session session, Session.Command command) {
            this.nodeId = nodeId;
            this.fileSystem = fileSystem;
            this.sshClient = sshClient;
            this.forwardingConnectListener = forwardingConnectListener;
            this.forwarding = forwarding;
            this.session = session;
            this.command = command;
        }

        @Override
        public void close() throws Exception
        {
            IOUtil.close(fileSystem);

            // 0x03 is the character for CTRL-C -> send it to the remote PTY
            session.getOutputStream().write(0x03);
            // also send TERM signal
            command.signal(Signal.TERM);
            try
            {
                command.join(10, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                // timeout? error? too late, try to kill the process
                command.signal(Signal.KILL);
            }
            IOUtil.close(command);
            IOUtil.close(session);
            if (!LocalHostLauncher.skipDiskCleanup())
            {
                try (SFTPClient sftpClient = sshClient.newStatefulSFTPClient())
                {
                    deltree(sftpClient, "." + NodeFileSystemProvider.PREFIX + "/" + nodeId.getClusterId());
                }
            }
            IOUtil.close(forwardingConnectListener);
            IOUtil.close(forwarding);
            IOUtil.close(sshClient);
        }

        private static void deltree(SFTPClient sftpClient, String path) throws IOException
        {
            List<RemoteResourceInfo> ls = sftpClient.ls(path);
            for (RemoteResourceInfo l : ls)
            {
                if (l.isDirectory())
                    deltree(sftpClient, l.getPath());
                else
                    sftpClient.rm(l.getPath());
            }
            sftpClient.rmdir(path);
        }
    }

    private static class SocketForwardingConnectListener implements ConnectListener, AutoCloseable
    {
        private final String threadNamePrefix;
        private final SocketAddress addr;
        private Socket socket;
        private Channel.Forwarded channel;

        private SocketForwardingConnectListener(String threadNamePrefix, SocketAddress addr)
        {
            this.threadNamePrefix = threadNamePrefix;
            this.addr = addr;
        }

        @Override
        public void close()
        {
            IOUtil.close(channel, socket);
        }

        @Override
        public void gotConnect(Channel.Forwarded channel) throws IOException
        {
            this.channel = channel;
            socket = new Socket();
            socket.setSendBufferSize(channel.getLocalMaxPacketSize());
            socket.setReceiveBufferSize(channel.getRemoteMaxPacketSize());
            socket.connect(addr);

            channel.confirm();

            new StreamCopier(socket.getInputStream(), channel.getOutputStream(), channel.getRemoteMaxPacketSize(), false)
                .spawnDaemon(threadNamePrefix + "-soc2chan");

            new StreamCopier(channel.getInputStream(), socket.getOutputStream(), channel.getLocalMaxPacketSize(), false)
                .spawnDaemon(threadNamePrefix + "-chan2soc");
        }
    }
}
