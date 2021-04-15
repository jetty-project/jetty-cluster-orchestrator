package net.webtide.cluster.configuration;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import net.webtide.cluster.util.SerializableSupplier;

public class Jvm implements Serializable
{
    public static Jvm DEFAULT = new Jvm(() -> "java");

    private final SerializableSupplier<String> executableSupplier;
    private final List<String> opts;

    public Jvm(SerializableSupplier<String> executableSupplier, String... opts)
    {
        this.executableSupplier = executableSupplier;
        this.opts = Arrays.asList(opts);
    }

    public String executable()
    {
        return executableSupplier.get();
    }

    public List<String> getOpts()
    {
        return opts;
    }
}
