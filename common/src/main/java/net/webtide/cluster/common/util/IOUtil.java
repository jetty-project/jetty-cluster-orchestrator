package net.webtide.cluster.common.util;

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
}
