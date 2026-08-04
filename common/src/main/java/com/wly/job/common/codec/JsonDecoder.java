package com.wly.job.common.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Netty 入站 JSON 解码器：解析「4 字节长度前缀 + JSON 字节流」帧格式，反序列化为目标类型对象，
 * 与出站侧 {@link JsonEncoder} 成对使用。
 * <p>
 * 内置 TCP 半包/粘包保护：可读字节不足 4 字节或不足声明的数据长度时，回退读指针等待后续数据，
 * 待一次完整帧到达后再解码，保证单条调度 RPC 消息不被拆散或合并。
 */
public class JsonDecoder extends ByteToMessageDecoder {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Class<?> genericClass;

    public JsonDecoder(Class<?> genericClass) {
        this.genericClass = genericClass;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();
        int dataLength = in.readInt();
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }

        byte[] data = new byte[dataLength];
        in.readBytes(data);
        Object obj = mapper.readValue(data, genericClass);
        out.add(obj);
    }
}