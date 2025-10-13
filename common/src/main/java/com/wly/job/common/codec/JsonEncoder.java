package com.wly.job.common.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class JsonEncoder extends MessageToByteEncoder<Object> {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Class<?> genericClass;

    public JsonEncoder(Class<?> genericClass) {
        this.genericClass = genericClass;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if (genericClass.isInstance(msg)) {
            byte[] data = mapper.writeValueAsBytes(msg);
            out.writeInt(data.length);
            out.writeBytes(data);
        }
    }
}