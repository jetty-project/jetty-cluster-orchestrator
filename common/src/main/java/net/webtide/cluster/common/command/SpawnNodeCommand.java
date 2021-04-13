package net.webtide.cluster.common.command;

import java.util.List;

public class SpawnNodeCommand implements Command
{
    private final List<String> cmdLine;

    public SpawnNodeCommand(List<String> cmdLine)
    {
        this.cmdLine = cmdLine;
    }

    @Override
    public Object execute() throws Exception
    {
        try
        {
            ProcessBuilder processBuilder = new ProcessBuilder(cmdLine);
            processBuilder.inheritIO();
            Process process = processBuilder.start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
