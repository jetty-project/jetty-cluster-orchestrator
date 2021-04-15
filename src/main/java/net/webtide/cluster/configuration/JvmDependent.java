package net.webtide.cluster.configuration;

public interface JvmDependent
{
    Jvm jvm();
    JvmDependent jvm(Jvm jvm);
}
