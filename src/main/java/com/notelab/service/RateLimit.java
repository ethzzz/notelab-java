package com.notelab.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.notelab.common.AppConfig;

/**
 * 限流：与 Python 版 _rate_ok 一致。
 * 优先 Redis（键 "rl:<key>"，INCR + EXPIRE）；Redis 不可用则退回内存滑动窗口。
 * 与 Python 版共用同一个 Redis 实例，切流前后限流状态自然衔接。
 */
public final class RateLimit {

    private static final Map<String, List<Long>> BUCKETS = new ConcurrentHashMap<>();
    private static volatile boolean redisTried = false;
    private static volatile boolean redisOk = false;

    private RateLimit() {}

    public static boolean rateOk(String key, int limit, int windowSec) {
        if (tryRedis()) {
            try {
                String full = "rl:" + key;
                long count = redisIncr(full);
                if (count == 1) redisExpire(full, windowSec);
                return count <= limit;
            } catch (Exception e) {
                // 与 Python 一致：Redis 出错则落到内存限流
            }
        }
        return memoryOk(key, limit, windowSec);
    }

    private static synchronized boolean tryRedis() {
        if (redisTried) return redisOk;
        redisTried = true;
        try (RedisConn c = new RedisConn()) {
            c.ping();
            redisOk = true;
        } catch (Exception e) {
            redisOk = false;
        }
        return redisOk;
    }

    private static long redisIncr(String key) throws IOException {
        try (RedisConn c = new RedisConn()) {
            return c.incr(key);
        }
    }

    private static void redisExpire(String key, int sec) throws IOException {
        try (RedisConn c = new RedisConn()) {
            c.expire(key, sec);
        }
    }

    private static boolean memoryOk(String key, int limit, int windowSec) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowSec * 1000L;
        List<Long> bucket = BUCKETS.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.removeIf(t -> t <= cutoff);
            if (bucket.size() >= limit) return false;
            bucket.add(now);
            return true;
        }
    }

    /** 极简 RESP 客户端：PING / INCR / EXPIRE，行为对齐 Python redis.Redis(socket_connect_timeout=2, socket_timeout=2) */
    private static final class RedisConn implements AutoCloseable {
        private final Socket sock;
        private final OutputStream out;
        private final InputStream in;

        RedisConn() throws IOException {
            sock = new Socket();
            sock.connect(new InetSocketAddress(AppConfig.redisHost(), AppConfig.redisPort()), 2000);
            sock.setSoTimeout(2000);
            out = sock.getOutputStream();
            in = sock.getInputStream();
        }

        void ping() throws IOException {
            send("PING");
            expect("+PONG");
        }

        long incr(String key) throws IOException {
            send("INCR", key);
            String line = readLine();
            if (line.startsWith(":")) return Long.parseLong(line.substring(1));
            throw new IOException("INCR 响应异常: " + line);
        }

        void expire(String key, int sec) throws IOException {
            send("EXPIRE", key, String.valueOf(sec));
            readLine(); // :0 / :1
        }

        private void send(String... args) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append('*').append(args.length).append("\r\n");
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void expect(String prefix) throws IOException {
            String line = readLine();
            if (!line.startsWith(prefix)) throw new IOException("响应异常: " + line);
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') {
                    int n = in.read(); // \n
                    break;
                }
                sb.append((char) c);
            }
            return sb.toString();
        }

        @Override
        public void close() {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }
}
