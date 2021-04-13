package net.webtide.cluster.nodemanager;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class RemoteNode
{
    public static void main(String[] args) throws Exception
    {
        String nodeId = args[0];
        String connectString = args[1];
        CuratorFramework curator = CuratorFrameworkFactory.newClient(connectString, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();
        Runtime.getRuntime().addShutdownHook(new Thread(curator::close));

        try (CommandProcessor commandProcessor = new CommandProcessor(curator, nodeId))
        {
            commandProcessor.run();
        }
    }
}
