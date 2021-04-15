package net.webtide.cluster.rpc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.util.IOUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeProcess implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(NodeProcess.class);
    
    private final File rootPath;
    private final Process process;

    private NodeProcess(File rootPath, Process process)
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
        if (LOG.isDebugEnabled())
            LOG.debug("Starting node [{}] connecting to {}", nodeId, connectString);
        CuratorFramework curator = CuratorFrameworkFactory.newClient(connectString, new RetryNTimes(0, 0));
        curator.start();
        curator.blockUntilConnected();

        if (LOG.isDebugEnabled())
            LOG.debug("Node [{}] connected to {}", nodeId, connectString);
        RpcServer rpcServer = new RpcServer(curator, nodeId);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Node [{}] stopping", nodeId);
            try
            {
                rpcServer.abort();
            }
            catch (Exception e)
            {
                // ignore
            }
            curator.close();
            if (LOG.isDebugEnabled())
                LOG.debug("Node [{}] stopped", nodeId);
        }));

        rpcServer.run();
        if (LOG.isDebugEnabled())
            LOG.debug("Node [{}] disconnecting from {}", nodeId, connectString);
    }

    public static NodeProcess spawn(Jvm jvm, String hostId, String nodeId, String connectString) throws IOException
    {
        File nodeRootPath = defaultRootPath(nodeId);
        nodeRootPath.mkdirs();

        List<String> cmdLine = buildCommandLine(jvm, defaultLibPath(hostId), nodeId, connectString);
        return new NodeProcess(nodeRootPath, new ProcessBuilder(cmdLine)
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

    private static List<String> buildCommandLine(Jvm jvm, File libPath, String nodeId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable());
        cmdLine.addAll(jvm.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add(nodeId);
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
