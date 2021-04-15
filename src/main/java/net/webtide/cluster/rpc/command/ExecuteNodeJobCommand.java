package net.webtide.cluster.rpc.command;

import net.webtide.cluster.NodeJob;

public class ExecuteNodeJobCommand implements Command
{
    private final NodeJob nodeJob;

    public ExecuteNodeJobCommand(NodeJob nodeJob)
    {
        this.nodeJob = nodeJob;
    }

    @Override
    public Object execute() throws Exception
    {
        nodeJob.execute(null);
        return null;
    }
}
