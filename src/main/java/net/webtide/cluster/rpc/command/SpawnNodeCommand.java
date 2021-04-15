package net.webtide.cluster.rpc.command;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.rpc.RemoteNode;

public class SpawnNodeCommand implements Command
{
    private final Jvm jvm;
    private final String hostId;
    private final String remoteNodeId;
    private final String connectString;

    public SpawnNodeCommand(Jvm jvm, String hostId, String remoteNodeId, String connectString)
    {
        this.jvm = jvm;
        this.hostId = hostId;
        this.remoteNodeId = remoteNodeId;
        this.connectString = connectString;
    }

    @Override
    public Object execute()
    {
        try
        {
            RemoteNode.spawn(jvm, hostId, remoteNodeId, connectString);
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
