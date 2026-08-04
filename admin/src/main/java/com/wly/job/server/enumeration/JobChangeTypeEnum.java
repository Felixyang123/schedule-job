package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum JobChangeTypeEnum {
    REGISTER(1),
    EDIT(2),
    SWITCH(3),
    FINISHED(4),
    DELETE(5),
    REQUEUE(6);

    private final int code;
}
