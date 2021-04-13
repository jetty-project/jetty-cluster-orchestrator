package net.webtide.cluster.common.command;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class InstallClasspathFileCommand implements Command
{
    private final String hostname;
    private final String filename;
    private final byte[] contents;
    private final boolean append;

    public InstallClasspathFileCommand(String hostname, String filename, byte[] contents, boolean append)
    {
        this.hostname = hostname;
        this.filename = filename;
        this.contents = contents;
        this.append = append;
    }

    @Override
    public Object execute() throws Exception
    {
        File rootPath = new File(System.getProperty("user.home") + "/.wtc/" + hostname);
        File libPath = new File(rootPath, "lib");

        File cpEntry = new File(libPath, filename);
        if (!append)
            cpEntry.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(cpEntry, append))
        {
            os.write(contents);
        }

        return null;
    }
}
