import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author zhongping.czp
 * @version : JavaTCPProxy.java, v 0.1 2021年12月16日 10:48 上午 zhongping.czp Exp $
 */
public class JavaAIOProxy implements ThreadFactory, CompletionHandler<AsynchronousSocketChannel, Object> {

    private boolean debug;
    private boolean direct;
    private boolean serverSide;

    private int                             boss;
    private InetSocketAddress               local;
    private InetSocketAddress               upstream;
    private AsynchronousChannelGroup        bossGroup;
    private AsynchronousServerSocketChannel channel;

    private byte                              encodeKey    = 8;
    private AtomicInteger                     bossThreadId = new AtomicInteger();
    private ConcurrentLinkedQueue<ByteBuffer> buffPool     = new ConcurrentLinkedQueue();

    public JavaAIOProxy(InetSocketAddress local, InetSocketAddress upstream, int boss) {
        this.boss = boss;
        this.local = local;
        this.upstream = upstream;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public void setDirect(boolean direct) {
        this.direct = direct;
    }

    public void setServerSide(boolean serverSide) {
        this.serverSide = serverSide;
    }

    public void setEncodeKey(byte encodeKey) {
        this.encodeKey = encodeKey;
    }

    public ByteBuffer alloc(int size) {
        ByteBuffer byteBuffer = buffPool.poll();
        if (byteBuffer == null) {
            System.out.println("alloc from jvm");
            byteBuffer = ByteBuffer.allocate(size);
        } else {
            System.out.println("alloc from pool");
            byteBuffer.clear();
        }
        return byteBuffer;
    }

    public void recycling(ByteBuffer buffer) {
        buffPool.add(buffer);
    }

    public void start(boolean daemon) throws Exception {
        bossGroup = AsynchronousChannelGroup.withFixedThreadPool(boss, this);
        channel = AsynchronousServerSocketChannel.open(bossGroup);
        //channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        //channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        channel.bind(local).accept(null, this);
        System.out.println(String.format("listen at:%s boss:%s upstream:%s", local, boss, upstream));
        if (!daemon) { Thread.currentThread().join(); }
    }

    public void stop() throws IOException {
        if (channel != null) { channel.close(); }
        if (bossGroup != null) { bossGroup.shutdown(); }
    }

    public static void main(String[] args) throws Exception {
        byte key = Byte.parseByte(System.getProperty("key", "1"));
        boolean debug = Boolean.parseBoolean(System.getProperty("debug", "false"));
        boolean direct = Boolean.parseBoolean(System.getProperty("direct", "false"));
        boolean serverSide = Boolean.parseBoolean(System.getProperty("serverSide", "false"));

        String upstreamHost = System.getProperty("host");
        int upstreamPort = Integer.parseInt(System.getProperty("dport"));
        int boss = Integer.parseInt(System.getProperty("boss", "4"));
        int port = Integer.parseInt(System.getProperty("port", "80"));
        InetSocketAddress local = new InetSocketAddress("0.0.0.0", port);
        InetSocketAddress upstream = new InetSocketAddress(upstreamHost, upstreamPort);
        JavaAIOProxy proxy = new JavaAIOProxy(local, upstream, boss);
        proxy.setServerSide(serverSide);
        proxy.setDirect(direct);
        proxy.setEncodeKey(key);
        proxy.setDebug(debug);
        proxy.start(false);
        proxy.stop();
    }

    class ConnectHandler implements CompletionHandler<Void, Object> {

        final AsynchronousSocketChannel client;
        final AsynchronousSocketChannel upstreamChannel;

        final ByteBuffer clientBuffer   = alloc(2048);
        final ByteBuffer upstreamBuffer = alloc(2048);

        public ConnectHandler(AsynchronousSocketChannel client, AsynchronousSocketChannel upstreamChannel) {
            this.client = client;
            this.upstreamChannel = upstreamChannel;
        }

        @Override
        public void completed(Void result, Object attachment) {
            System.out.println("connect:" + upstreamChannel);
            forward(client, upstreamChannel, clientBuffer, attachment, true);
            forward(upstreamChannel, client, upstreamBuffer, attachment, false);
        }

        @Override
        public void failed(Throwable exc, Object attachment) {
            exc.printStackTrace();
            close(client);
            close(upstreamChannel);
            recycling(upstreamBuffer);
            recycling(clientBuffer);
        }
    }

    private void forward(AsynchronousSocketChannel src, AsynchronousSocketChannel dst, ByteBuffer srcBuffer, Object attachment,
                         boolean isClient) {
        src.read(srcBuffer, attachment, new CompletionHandler<Integer, Object>() {

            @Override
            public void completed(Integer result, Object attachment) {
                // System.out.println("read:" + result);
                if (result > 0) {
                    pipeData(src, dst, srcBuffer, isClient, this);
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                System.out.println(exc.getMessage());
                if (debug) { exc.printStackTrace(); }
                close(src);
                close(dst);
                recycling(srcBuffer);
            }
        });
    }

    private void close(AsynchronousSocketChannel channel) {
        try {
            System.out.println("close:" + channel);
            if (channel.isOpen()) { channel.close(); }
        } catch (IOException exc) {
            System.out.println(exc.getMessage());
            if (debug) { exc.printStackTrace(); }
        }
    }

    private void pipeData(AsynchronousSocketChannel src, AsynchronousSocketChannel dst, ByteBuffer buffer, boolean isClientData,
                          CompletionHandler<Integer, Object> completionHandler) {
        buffer.flip();
        if (!direct && isClientData) {
            if (serverSide) { decode(buffer); } else { encode(buffer); }
        }
        if (debug) { System.out.println(new String(buffer.array(), 0, buffer.limit())); }

        dst.write(buffer, null, new CompletionHandler<Integer, Object>() {

            @Override
            public void completed(Integer result, Object attachment) {
                if (buffer.hasRemaining()) {
                    dst.write(buffer, null, this);
                } else {
                    buffer.flip();
                    src.read(buffer, attachment, completionHandler);
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                close(dst);
                recycling(buffer);
                System.out.println(exc.getMessage());
                if (debug) { exc.printStackTrace(); }
            }
        });
    }

    private void encode(ByteBuffer data) {
        int _key = encodeKey;
        int len = data.limit();
        byte[] array = data.array();
        //only encode header
        for (int i = 0; i < len; i++) {
            array[i] ^= _key;
        }
    }

    private void decode(ByteBuffer data) {
        int _key = encodeKey;
        int len = data.limit();
        byte[] array = data.array();
        for (int i = 0; i < len; i++) {
            array[i] ^= _key;
        }
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, "proxy-" + bossThreadId.getAndDecrement());
    }

    @Override
    public void completed(AsynchronousSocketChannel client, Object attachment) {
        try {
            System.out.println(Thread.currentThread() + "-accept:" + client);
            channel.accept(attachment, this);
            AsynchronousSocketChannel upstreamChannel = AsynchronousSocketChannel.open();
            upstreamChannel.connect(upstream, null, new ConnectHandler(client, upstreamChannel));
        } catch (Throwable exc) {
            System.out.println(exc.getMessage());
            if (debug) { exc.printStackTrace(); }
        }
    }

    @Override
    public void failed(Throwable exc, Object attachment) {
        exc.printStackTrace();
    }
}
