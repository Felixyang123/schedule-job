package com.wly.job.common.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty 出站 JSON 编码器：将消息序列化为「4 字节长度前缀 + JSON 字节流」帧格式，
 * 与入站侧 {@link JsonDecoder} 成对使用，构成 Admin 与 Worker 之间 TCP 调度 RPC 的编解码。
 * <p>
 * 仅对构造时指定的目标类型（如 {@link com.wly.job.common.bean.ScheduleJobResponse}）进行编码，
 * 其余类型消息直接透传不处理。序列化在 Netty I/O 线程执行，无阻塞操作。
 */
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