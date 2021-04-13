package net.webtide.cluster.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class IOUtil
{
    public static void close(AutoCloseable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
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
}
