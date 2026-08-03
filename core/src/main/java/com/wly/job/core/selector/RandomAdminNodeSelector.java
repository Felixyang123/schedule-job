package com.wly.job.core.selector;

import java.util.concurrent.ThreadLocalRandom;

public class RandomAdminNodeSelector implements AdminNodeSelector {

    @Override
    public int select(int size, String key) {
        return ThreadLocalRandom.current().nextInt(size);
    }
}
