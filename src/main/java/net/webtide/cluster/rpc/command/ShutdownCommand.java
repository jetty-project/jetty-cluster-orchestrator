package net.webtide.cluster.rpc.command;

public class ShutdownCommand implements Command
{
    @Override
    public Object execute() throws Exception
    {
        throw new ShutdownException();
    }

    public static class ShutdownException extends Exception
    {
    }
}
