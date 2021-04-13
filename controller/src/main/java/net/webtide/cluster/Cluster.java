package net.webtide.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.webtide.cluster.common.Jvm;
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

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            NodeArrayTopology topology = nodeArrayConfiguration.topology();

            topology.nodes().stream().map(Node::getHostname).forEach(hostname ->
            {
                remoteHostLauncher.launch(hostname, zkServer.getConnectString());
                hostClients.put(hostname, new RpcClient(curator, hostname));
            });

            for (Node node : topology.nodes())
            {
                String remoteNodeId = node.getHostname() + "/" + nodeArrayConfiguration.id() + "/" + node.getId();

                Jvm jvm = nodeArrayConfiguration.jvmSettings().jvm();
                List<String> opts = nodeArrayConfiguration.jvmSettings().getOpts();
                hostClients.get(node.getHostname()).call(new SpawnNodeCommand(jvm, opts, remoteNodeId, zkServer.getConnectString()));
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
        IOUtil.close(remoteHostLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
