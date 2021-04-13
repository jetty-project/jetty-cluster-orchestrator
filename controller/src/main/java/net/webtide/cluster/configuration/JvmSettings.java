package net.webtide.cluster.configuration;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import net.webtide.cluster.util.SerializableSupplier;

public class JvmSettings implements Serializable
{
    public static JvmSettings DEFAULT = new JvmSettings(() -> new Jvm(null, "unknown"));

    private final SerializableSupplier<Jvm> jvmSupplier;
    private final List<String> opts;

    public JvmSettings(SerializableSupplier<Jvm> jvmSupplier, String... opts)
    {
        this.jvmSupplier = jvmSupplier;
        this.opts = Arrays.asList(opts);
    }

    public Jvm jvm()
    {
        return jvmSupplier.get();
    }

    public List<String> getOpts()
    {
        return opts;
    }
}
