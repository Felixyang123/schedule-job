package com.wly.job.common.utils;

import com.wly.job.common.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 网络工具类：自动探测本机可用的非回环 IPv4 地址，作为 Worker 向 Admin 注册的实例宿主 IP。
 * <p>
 * 遍历所有已启用的非回环网卡，优先返回 IPv4 地址；探测失败或无可用地址时抛出
 * {@link ScheduleException}。
 */
@Slf4j
public class NetworkUtils {

    public static String getServerIp() {
        try {
            // 遍历所有的网络接口
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // 跳过未启用和回环接口
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                // 遍历该接口下的所有IP地址
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    // 优先返回IPv4地址
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Get local ip fail: ", e);
            throw new ScheduleException("Get local ip fail");
        }
        throw new ScheduleException("No available local ip found");
    }
}
