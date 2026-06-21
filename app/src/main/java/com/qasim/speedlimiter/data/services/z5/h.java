package com.qasim.speedlimiter.data.services.z5;

import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

public final class h implements Runnable {

    private final BlockingQueue<ByteBuffer> packetOutputQueue;
    private final Selector tcpSelector;
    private final u1 speedController;

    public h(BlockingQueue<ByteBuffer> outputQueue, Selector selector, u1 throttler) {
        this.packetOutputQueue = outputQueue;
        this.tcpSelector = selector;
        this.speedController = throttler;
    }

    @Override
    public void run() {
        while (!Thread.interrupted()) {
            try {
                if (this.tcpSelector.select() == 0) {
                    Thread.sleep(10L);
                    continue;
                }
                
                Iterator<SelectionKey> keys = this.tcpSelector.selectedKeys().iterator();
                while (keys.hasNext() && !Thread.interrupted()) {
                    SelectionKey key = keys.next();
                    if (key.isValid()) {
                        keys.remove();
                        
                        SocketChannel channel = (SocketChannel) key.channel();
                        ByteBuffer buffer = ByteBuffer.allocate(32768);
                        
                        if (key.isConnectable()) {
                            if (channel.isConnectionPending()) {
                                try {
                                    channel.finishConnect();
                                } catch (IOException e) {
                                    key.cancel();
                                    channel.close();
                                    continue;
                                }
                            }
                        } else if (key.isReadable()) {
                            int readBytes = channel.read(buffer);
                            if (readBytes > 0) {
                                buffer.flip();
                                
                                // الفرملة الذكية الصارمة المأخوذة من تطبيقهم الناجح لحزم الـ TCP
                                if (this.speedController != null) {
                                    this.speedController.a(readBytes);
                                }
                                
                                this.packetOutputQueue.put(buffer);
                            } else if (readBytes == -1) {
                                key.cancel();
                                channel.close();
                            }
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                Log.e("VpnTcpEngine", "Error in TCP Selector routing", e);
                return;
            }
        }
    }
}
