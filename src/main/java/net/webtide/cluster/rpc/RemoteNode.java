package net.webtide.cluster.rpc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.util.IOUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class RemoteNode implements AutoCloseable
{
    private final File rootPath;
    private final java.lang.Process process;

    private RemoteNode(File rootPath, java.lang.Process process)
    {
        this.rootPath = rootPath;
        this.process = process;
    }

    @Override
    public void close()
    {
        process.destroy();
        try
        {
            process.waitFor();
        }
        catch (InterruptedException e)
        {
            // ignore
        }
        File parentPath = rootPath.getParentFile();
        if (IOUtil.deltree(rootPath) && parentPath != null)
        {
            String[] files = parentPath.list();
            if (files != null && files.length == 0)
                IOUtil.deltree(parentPath);
        }
    }

    public static void main(String[] args) throws Exception
    {
        String nodeId = args[0];
        String connectString = args[1];
        System.out.println("Starting remote node [" + nodeId + "] connecting to " + connectString);
        CuratorFramework curator = CuratorFrameworkFactory.newClient(connectString, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        System.out.println("Remote node [" + nodeId + "] connected to " + connectString);
        RpcServer rpcServer = new RpcServer(curator, nodeId);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            System.out.println("Remote node [" + nodeId + "] stopping");
            try
            {
                rpcServer.abort();
            }
            catch (Exception e)
            {
                // ignore
            }
            curator.close();
            System.out.println("Remote node [" + nodeId + "] stopped");
        }));

        rpcServer.run();
        System.out.println("Remote node [" + nodeId + "] disconnecting from " + connectString);
    }

    public static RemoteNode spawn(Jvm jvm, String hostId, String remoteNodeId, String connectString) throws IOException
    {
        File nodeRootPath = defaultRootPath(remoteNodeId);
        nodeRootPath.mkdirs();

        List<String> cmdLine = remoteNodeCommandLine(jvm, defaultLibPath(hostId), remoteNodeId, connectString);
        return new RemoteNode(nodeRootPath, new ProcessBuilder(cmdLine)
            .directory(nodeRootPath)
            .inheritIO()
            .start());
    }

    private static File defaultRootPath(String hostId)
    {
        return new File(System.getProperty("user.home") + "/.wtc/" + hostId);
    }

    private static File defaultLibPath(String hostId)
    {
        File rootPath = defaultRootPath(hostId);
        return new File(rootPath, "lib");
    }

    private static List<String> remoteNodeCommandLine(Jvm jvm, File libPath, String remoteNodeId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable());
        cmdLine.addAll(jvm.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add(RemoteNode.class.getName());
        cmdLine.add(remoteNodeId);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private static String buildClassPath(File libPath)
    {
        File[] entries = libPath.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File entry : entries)
        {
            sb.append(entry.getPath()).append(File.pathSeparatorChar);
        }
        if (sb.length() > 0)
            sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }
}
