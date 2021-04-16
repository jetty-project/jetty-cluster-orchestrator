package net.webtide.cluster.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IOUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(IOUtil.class);

    public static void close(AutoCloseable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("", e);
        }
    }

    public static void copy(InputStream is, OutputStream os) throws IOException
    {
        byte[] buffer = new byte[1024];
        while (true)
        {
            int read = is.read(buffer);
            if (read == -1)
            {
                return;
            }
            os.write(buffer, 0, read);
        }
    }

    public static boolean deltree(File folder) {
        File[] files = folder.listFiles();
        if (files != null)
        {
            for (File file : files) {
                deltree(file);
            }
        }
        return folder.delete();
    }
}
