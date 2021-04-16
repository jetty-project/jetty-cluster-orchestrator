package net.webtide.cluster.configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.LocalSourceFile;
import net.webtide.cluster.rpc.NodeProcess;
import net.webtide.cluster.util.IOUtil;

public class SshRemoteHostLauncher implements HostLauncher, JvmDependent
{
    private final Map<String, RemoteNodeHolder> nodes = new HashMap<>();
    private Jvm jvm;

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
    public void launch(String hostname, String hostId, String rendezVous) throws Exception
    {
        if (nodes.containsKey(hostname))
            throw new IllegalArgumentException("ssh launcher already launched node on host " + hostname);

        SSHClient sshClient = new SSHClient();
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.loadKnownHosts();
        sshClient.connect(hostname);

        sshClient.authPublickey(System.getProperty("user.name"));
        Session session = sshClient.startSession();
        session.allocateDefaultPTY();

        List<String> remoteClasspathEntries = new ArrayList<>();
        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String classpathEntry : classpathEntries)
        {
            File cpFile = new File(classpathEntry);
            remoteClasspathEntries.add(".wtc/" + hostId + "/lib/" + cpFile.getName());
            if (!cpFile.isDirectory())
                copyFile(sshClient, hostId, cpFile.getName(), new FileSystemFile(cpFile));
            else
                copyDir(sshClient, hostId, cpFile, 1);
        }
        String remoteClasspath = String.join(":", remoteClasspathEntries);
        String cmdLine = String.join(" ", buildCommandLine(jvm, remoteClasspath, hostId, rendezVous));

        Session.Command cmd = session.exec(cmdLine);
        new StreamCopier(cmd.getInputStream(), System.out, net.schmizz.sshj.common.LoggerFactory.DEFAULT)
            .bufSize(1)
            .spawnDaemon("stdout-" + hostname);
        new StreamCopier(cmd.getErrorStream(), System.err, net.schmizz.sshj.common.LoggerFactory.DEFAULT)
            .bufSize(1)
            .spawnDaemon("stderr-" + hostname);

        RemoteNodeHolder remoteNodeHolder = new RemoteNodeHolder(hostId, sshClient, session, cmd);
        nodes.put(hostname, remoteNodeHolder);
    }

    private static List<String> buildCommandLine(Jvm jvm, String remoteClasspath, String nodeId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable());
        cmdLine.addAll(jvm.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(remoteClasspath);
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add(nodeId);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private void copyFile(SSHClient sshClient, String hostId, String filename, LocalSourceFile localSourceFile) throws Exception
    {
        String destFilename = ".wtc/" + hostId + "/lib/" + filename;
        String parentFilename = destFilename.substring(0, destFilename.lastIndexOf('/'));
        try (Session session = sshClient.startSession())
        {
            try (Session.Command cmd = session.exec("mkdir -p \"" + parentFilename + "\""))
            {
                cmd.join();
            }
        }
        sshClient.newSCPFileTransfer().upload(localSourceFile, destFilename);
    }

    private void copyDir(SSHClient sshClient, String hostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (!file.isDirectory())
            {
                String filename = file.getName();
                File currentFile = file;
                for (int i = 0; i < depth; i++)
                {
                    currentFile = currentFile.getParentFile();
                    filename = currentFile.getName() + "/" + filename;
                }
                copyFile(sshClient, hostId, filename, new FileSystemFile(file));
            }
            else
            {
                copyDir(sshClient, hostId, file, depth + 1);
            }
        }
    }

    private static class RemoteNodeHolder implements AutoCloseable {
        RemoteNodeHolder(String hostId, SSHClient sshClient, Session session, Session.Command command) {
            this.hostId = hostId;
            this.sshClient = sshClient;
            this.session = session;
            this.command = command;
        }

        private final String hostId;
        private final SSHClient sshClient;
        private final Session session;
        private final Session.Command command;

        @Override
        public void close() throws Exception
        {
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
                // timeout, ignore.
            }
            command.close();
            try (Session session = sshClient.startSession())
            {
                String folderName = ".wtc/" + hostId;
                try (Session.Command cmd = session.exec("rm -fr \"" + folderName + "\""))
                {
                    cmd.join();
                }
            }
            IOUtil.close(command);
            IOUtil.close(session);
            IOUtil.close(sshClient);
        }
    }
}