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

package org.mortbay.jetty.orchestrator.ssh.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
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
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.JvmDependent;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.NodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.launcher.AbstractHostLauncher;
import org.mortbay.jetty.orchestrator.localhost.launcher.LocalLauncher;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.ssh.configuration.SshNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.ssh.nodefs.SFTPNodeFileSystemFactory;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SshRemoteHostLauncher extends AbstractHostLauncher implements JvmDependent
{
    private static final Logger LOG = LoggerFactory.getLogger(SshRemoteHostLauncher.class);
    private static final List<String> COMMON_WIN_UNAMES = Arrays.asList("Windows", "CYGWIN", "MINGW", "MSYS", "UWIN");
    private static final List<String> DEFAULT_IDENTITY_FILENAMES = Arrays.asList("id_rsa", "id_ecdsa", "id_ed25519", "id_dsa");

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

    private ZooKeeperServer zkServer;

    @Override
    public String initialize() throws Exception {
        zkServer = new ZooKeeperServer();
        return zkServer.getConnectString();
    }

    @Override
    protected Class<? extends NodeArrayConfiguration> configurationType()
    {
        return SshNodeArrayConfiguration.class;
    }

    @Override
    protected void closeHosts()
    {
        nodes.values().forEach(IOUtil::close);
        nodes.clear();
        IOUtil.close(zkServer);
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
    protected String launchHost(GlobalNodeId globalNodeId, Node node, String connectString, String... extraArgs) throws Exception
    {
        long start = System.nanoTime();
        GlobalNodeId nodeId = globalNodeId.getHostGlobalId();
        if (LOG.isDebugEnabled())
            LOG.debug("start launch of node: {}", nodeId.getHostname());
        if (!nodeId.equals(globalNodeId))
            throw new IllegalArgumentException("node id is not the one of a host node");

        SshClient sshClient = SshClient.setUpDefaultClient();
        FileSystem fileSystem = null;
        ExplicitPortForwardingTracker forwardingTracker = null;
        ClientChannel execChannel = null;
        ClientSession session = null;
        try
        {
            sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE); // or a known-hosts verifier instead?
            // The client must also accept incoming "forwarded-tcpip" channels for the remote port forwarding
            // set up below to work: the default forwarding filter is reject-all, independent of the server's own.
            sshClient.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
            sshClient.start();
            session = sshClient.connect(username, nodeId.getHostname(), port).verify().getSession();

            if (LOG.isDebugEnabled())
                LOG.debug("ssh to {} with username {} and empty password {}", nodeId.getHostname(), username, password == null);

            if (password == null)
                addDefaultPublicKeyIdentities(session); // public key auth
            else
                session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(new String(password))); // pw auth, possibly empty
            session.auth().verify();

            // detect windows
            boolean windows = isWindows(session);

            // do remote port forwarding
            int zkPort = Integer.parseInt(connectString.split(":")[1]);
            forwardingTracker = session.createRemotePortForwardingTracker(
                new SshdSocketAddress("localhost", 0), // remote port, dynamically choose one
                new SshdSocketAddress("localhost", zkPort));
            String remoteConnectString = "localhost:" + forwardingTracker.getBoundAddress().getPort();

            HashMap<String, Object> env = new HashMap<>();
            env.put(SftpClient.class.getName(), SftpClientFactory.instance().createSftpClient(session));
            env.put(SFTPNodeFileSystemFactory.IS_WINDOWS_ENV_PROPERTY, windows);
            fileSystem = FileSystems.newFileSystem(URI.create(NodeFileSystemProvider.PREFIX + ":" + nodeId.getHostId()), env);

            List<String> remoteClasspathEntries = new ArrayList<>();
            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            String delimiter = windows ? "\\" : "/";
            try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session))
            {
                for (String classpathEntry : classpathEntries)
                {
                    Path cpPath = Paths.get(classpathEntry);
                    String cpFileName = cpPath.getFileName().toString();
                    if (!cpFileName.endsWith(".jar") && !cpFileName.endsWith(".JAR"))
                        remoteClasspathEntries.add("." + NodeFileSystemProvider.PREFIX + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + cpFileName);
                    if (Files.isDirectory(cpPath))
                        copyDir(sftpClient, nodeId.getHostId(), cpPath, 1);
                    else
                        copyFile(sftpClient, nodeId.getHostId(), cpFileName, cpPath);
                }
            }
            remoteClasspathEntries.add("." + NodeFileSystemProvider.PREFIX + delimiter + nodeId.getHostId() + delimiter + NodeProcess.CLASSPATH_FOLDER_NAME + delimiter + "*");

            String cmdLine = String.join(" ", buildCommandLine(fileSystem, jvm, remoteClasspathEntries, windows ? ";" : ":", nodeId.getHostId(), nodeId.getHostname(), remoteConnectString, extraArgs));
            execChannel = session.createExecChannel(cmdLine);
            execChannel.setOut(System.out);
            execChannel.setErr(System.err);
            execChannel.open().verify();

            RemoteNodeHolder remoteNodeHolder = new RemoteNodeHolder(nodeId, fileSystem, sshClient, session, forwardingTracker, execChannel);
            nodes.put(nodeId.getHostname(), remoteNodeHolder);
            return remoteConnectString;
        }
        catch (Exception e)
        {
            IOUtil.close(fileSystem, execChannel, forwardingTracker, session);
            sshClient.stop();
            throw new Exception("Error launching host '" + nodeId.getHostname() + "'", e);
        }
        finally
        {
            if (LOG.isDebugEnabled())
                LOG.debug("time to start host {}: {}ms", nodeId.getHostname(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static void addDefaultPublicKeyIdentities(ClientSession session) throws IOException, GeneralSecurityException
    {
        Path sshDir = Paths.get(System.getProperty("user.home"), ".ssh");
        for (String filename : DEFAULT_IDENTITY_FILENAMES)
        {
            Path keyFile = sshDir.resolve(filename);
            if (!Files.isReadable(keyFile))
                continue;
            for (KeyPair keyPair : SecurityUtils.getKeyPairResourceParser().loadKeyPairs(session, keyFile, FilePasswordProvider.EMPTY))
                session.addPublicKeyIdentity(keyPair);
        }
    }

    private static boolean isWindows(ClientSession session) throws IOException
    {
        try (ClientChannel channel = session.createExecChannel("uname -s"))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            channel.setOut(out);
            channel.open().verify();
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
            String output = out.toString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            Integer exitStatus = channel.getExitStatus();
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

    private static List<String> buildCommandLine(FileSystem fileSystem, Jvm jvm, List<String> remoteClasspathEntries, String delimiter, String nodeId, String hostname, String connectString, String... extraArgs)
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
        for (String extraArg : extraArgs)
            cmdLine.add("\"" + extraArg + "\"");
        return cmdLine;
    }

    private static List<String> filterOutEmptyStrings(List<String> opts)
    {
        return opts.stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    private static void copyFile(SftpClient sftpClient, String hostId, String filename, Path localPath) throws IOException
    {
        String destFilename = "." + NodeFileSystemProvider.PREFIX + "/" + hostId + "/" + NodeProcess.CLASSPATH_FOLDER_NAME + "/" + filename;
        String parentFilename = destFilename.substring(0, destFilename.lastIndexOf('/'));

        mkdirs(sftpClient, parentFilename);
        try (InputStream is = Files.newInputStream(localPath); OutputStream os = sftpClient.write(destFilename))
        {
            IOUtil.copy(is, os);
        }
    }

    private static void mkdirs(SftpClient sftpClient, String path) throws IOException
    {
        boolean absolute = path.startsWith("/");
        StringBuilder partial = new StringBuilder();
        for (String segment : path.split("/"))
        {
            if (segment.isEmpty())
                continue;
            if (absolute || partial.length() > 0)
                partial.append('/');
            partial.append(segment);
            try
            {
                sftpClient.mkdir(partial.toString());
            }
            catch (SftpException e)
            {
                if (e.getStatus() != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS)
                    throw e;
            }
        }
    }

    private static void copyDir(SftpClient sftpClient, String hostId, Path cpPath, int depth) throws IOException
    {
        if (!Files.isDirectory(cpPath))
            return;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(cpPath))
        {
            for (Path file : files)
            {
                if (Files.isDirectory(file))
                {
                    copyDir(sftpClient, hostId, file, depth + 1);
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
                    copyFile(sftpClient, hostId, filename, file);
                }
            }
        }
    }

    private static class RemoteNodeHolder implements AutoCloseable {
        private final GlobalNodeId nodeId;
        private final FileSystem fileSystem;
        private final SshClient sshClient;
        private final ClientSession session;
        private final ExplicitPortForwardingTracker forwardingTracker;
        private final ClientChannel execChannel;

        private RemoteNodeHolder(GlobalNodeId nodeId, FileSystem fileSystem, SshClient sshClient, ClientSession session, ExplicitPortForwardingTracker forwardingTracker, ClientChannel execChannel) {
            this.nodeId = nodeId;
            this.fileSystem = fileSystem;
            this.sshClient = sshClient;
            this.session = session;
            this.forwardingTracker = forwardingTracker;
            this.execChannel = execChannel;
        }

        @Override
        public void close()
        {
            IOUtil.close(fileSystem);
            if (!LocalLauncher.skipDiskCleanup())
            {
                try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session))
                {
                    deltree(sftpClient, "." + NodeFileSystemProvider.PREFIX + "/" + nodeId.getClusterId());
                }
                catch (Exception e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("error deleting temporary files using ssh client {}", sshClient, e);
                }
            }
            // close the exec channel and session immediately (rather than gracefully) first, to make sure
            // closing them does not block on the long-running remote process
            execChannel.close(true);
            IOUtil.close(forwardingTracker);
            session.close(true);
            try
            {
                sshClient.stop();
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("error stopping ssh client {}", sshClient, e);
            }
        }

        private static void deltree(SftpClient sftpClient, String path) throws IOException
        {
            for (SftpClient.DirEntry entry : sftpClient.readDir(path))
            {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name))
                    continue;
                String childPath = path + "/" + name;
                if (entry.getAttributes().isDirectory())
                    deltree(sftpClient, childPath);
                else
                    sftpClient.remove(childPath);
            }
            sftpClient.rmdir(path);
        }
    }
}
