package net.webtide.cluster.rpc.command;

import net.webtide.cluster.ClusterTools;
import net.webtide.cluster.NodeJob;

public class ExecuteNodeJobCommand implements Command
{
    private final NodeJob nodeJob;

    public ExecuteNodeJobCommand(NodeJob nodeJob)
    {
        this.nodeJob = nodeJob;
    }

    @Override
    public Object execute(ClusterTools clusterTools) throws Exception
    {
        nodeJob.execute(clusterTools);
        return null;
    }
}
