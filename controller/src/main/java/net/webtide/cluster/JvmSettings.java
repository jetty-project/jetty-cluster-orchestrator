package net.webtide.cluster;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import net.webtide.cluster.common.Jvm;

public class JvmSettings
{
    private final Supplier<Jvm> jvmSupplier;
    private final List<String> opts;

    public JvmSettings(Supplier<Jvm> jvmSupplier, String... opts)
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
