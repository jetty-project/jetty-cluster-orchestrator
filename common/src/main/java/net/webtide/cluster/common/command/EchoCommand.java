package net.webtide.cluster.common.command;

public class EchoCommand implements Command
{
    private final Object param;

    public EchoCommand(Object param)
    {
        this.param = param;
    }

    @Override
    public Object execute() throws Exception
    {
        return param;
    }
}
