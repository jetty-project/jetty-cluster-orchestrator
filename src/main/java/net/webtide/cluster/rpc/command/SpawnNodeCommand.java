package net.webtide.cluster.rpc.command;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.rpc.NodeProcess;

public class SpawnNodeCommand implements Command
{
    private final Jvm jvm;
    private final String hostId;
    private final String nodeId;
    private final String connectString;

    public SpawnNodeCommand(Jvm jvm, String hostId, String nodeId, String connectString)
    {
        this.jvm = jvm;
        this.hostId = hostId;
        this.nodeId = nodeId;
        this.connectString = connectString;
    }

    @Override
    public Object execute()
    {
        try
        {
            NodeProcess.spawn(jvm, hostId, nodeId, connectString);
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
