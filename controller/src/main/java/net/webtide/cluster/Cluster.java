package net.webtide.cluster;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.webtide.cluster.common.Jvm;
import net.webtide.cluster.common.command.InstallClasspathFileCommand;
import net.webtide.cluster.common.command.SpawnNodeCommand;
import net.webtide.cluster.common.util.IOUtil;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.NodeArrayConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;

public class Cluster implements AutoCloseable
{
    private final String id;
    private final ClusterConfiguration configuration;
    private final RemoteHostLauncher remoteHostLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private final Map<String, RpcClient> hostClients = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = id;
        this.configuration = configuration;
        this.remoteHostLauncher = configuration.remotingConfiguration().buildRemoteNodeLauncher();

        init();
    }

    private void init() throws Exception
    {
        zkServer = new TestingServer(true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        List<String> hostnames = configuration.nodeArrays().stream()
            .flatMap(cfg -> cfg.topology().nodes().stream())
            .map(Node::getHostname)
            .distinct()
            .collect(Collectors.toList());
        for (String hostname : hostnames)
        {
            remoteHostLauncher.launch(hostname, zkServer.getConnectString());
            RpcClient rpcClient = new RpcClient(curator, hostname);
            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            for (String classpathEntry : classpathEntries)
            {
                File cpFile = new File(classpathEntry);
                if (!cpFile.isDirectory())
                {
                    String filename = cpFile.getName();
                    try (InputStream is = new FileInputStream(cpFile))
                    {
                        uploadFile(rpcClient, hostname, filename, is);
                    }
                }
                else
                {
                    uploadDir(rpcClient, hostname, cpFile, 1);
                }
            }
            hostClients.put(hostname, rpcClient);
        }

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            NodeArrayTopology topology = nodeArrayConfiguration.topology();

            for (Node node : topology.nodes())
            {
                String remoteNodeId = node.getHostname() + "/" + nodeArrayConfiguration.id() + "/" + node.getId();

                RpcClient rpcClient = hostClients.get(node.getHostname());
                Jvm jvm = nodeArrayConfiguration.jvmSettings().jvm();
                List<String> opts = nodeArrayConfiguration.jvmSettings().getOpts();
                rpcClient.call(new SpawnNodeCommand(jvm, opts, node.getHostname(), remoteNodeId, zkServer.getConnectString()));
            }
            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray(nodeArrayConfiguration.id(), nodeArrayConfiguration.topology(), curator));
        }
    }

    private void uploadFile(RpcClient rpcClient, String hostname, String filename, InputStream contents) throws Exception
    {
        byte[] buffer = new byte[128 * 1024];
        boolean append = false;
        while (true)
        {
            int read = contents.read(buffer);
            if (read == -1)
                break;
            if (read != buffer.length)
            {
                byte[] b = new byte[read];
                System.arraycopy(buffer, 0, b, 0, read);
                buffer = b;
            }
            rpcClient.call(new InstallClasspathFileCommand(hostname, filename, buffer, append));
            append = true;
        }
    }

    private void uploadDir(RpcClient rpcClient, String hostname, File cpFile, int depth) throws Exception
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
                try (InputStream is = new FileInputStream(file))
                {
                    uploadFile(rpcClient, hostname, filename, is);
                }
            }
            else
            {
                uploadDir(rpcClient, hostname, file, depth + 1);
            }
        }
    }

    @Override
    public void close()
    {
        for (NodeArray nodeArray : nodeArrays.values())
        {
            nodeArray.close();
        }
        hostClients.clear();
        nodeArrays.clear();
        IOUtil.close(remoteHostLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
