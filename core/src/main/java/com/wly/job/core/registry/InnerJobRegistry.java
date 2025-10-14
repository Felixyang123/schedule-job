package com.wly.job.core.registry;

import com.wly.job.core.invocation.InnerJob;

public interface InnerJobRegistry {

    boolean register(InnerJob job);

    InnerJob get(String jobname);
}
