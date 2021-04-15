package net.webtide.cluster.rpc;

import net.webtide.cluster.rpc.RpcServer;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class RemoteNode
{
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
}
