package net.webtide.cluster.common.command;

import net.webtide.cluster.common.NodeJob;

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
