package net.webtide.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final RemoteNodeLauncher remoteNodeLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private final Map<String, RpcClient> hostClients = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = id;
        this.configuration = configuration;
        this.remoteNodeLauncher = configuration.remotingConfiguration().buildRemoteNodeLauncher();

        init();
    }

    private void init() throws Exception
    {
        zkServer = new TestingServer(true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            NodeArrayTopology topology = nodeArrayConfiguration.topology();

            List<String> hostnames = topology.nodes().stream().map(Node::getHostname).collect(Collectors.toList());
            for (String hostname : hostnames)
            {
                remoteNodeLauncher.launch(hostname, zkServer.getConnectString());
                hostClients.put(hostname, new RpcClient(curator, hostname));
            }

            for (Node node : topology.nodes())
            {
                String remoteNodeId = node.getHostname() + "/" + nodeArrayConfiguration.id() + "/" + node.getId();

                List<String> cmdLine = new ArrayList<>();
                cmdLine.add(nodeArrayConfiguration.jvmSettings().jvm().getHome() + "/bin/java");
                cmdLine.addAll(nodeArrayConfiguration.jvmSettings().getOpts());
                cmdLine.addAll(Arrays.asList("-jar", System.getProperty("java.io.tmpdir") + "/node.jar", remoteNodeId, zkServer.getConnectString()));

                hostClients.get(node.getHostname()).call(new SpawnNodeCommand(cmdLine));
            }
            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray(nodeArrayConfiguration.id(), nodeArrayConfiguration.topology(), curator));
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
        IOUtil.close(remoteNodeLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
