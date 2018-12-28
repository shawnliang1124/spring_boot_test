package com.unisound.iot.service.aio.protcol;


import com.unisound.iot.common.modle.aio.BufferPage;
import com.unisound.iot.common.modle.aio.VirtualBuffer;
import com.unisound.iot.service.aio.BufferOutputStream;
import com.unisound.iot.service.aio.Function;
import com.unisound.iot.service.aio.MessageProcessor;
import com.unisound.iot.service.aio.enums.StateMachineEnum;
import com.unisound.iot.service.aio.handler.ReadCompletionHandler;
import com.unisound.iot.service.aio.handler.WriteCompletionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * AIO传输层会话。
 *
 * <p>
 * AioSession为smart-socket最核心的类，封装{@link java.nio.channels.AsynchronousSocketChannel} API接口，简化IO操作。
 * </p>
 * <p>
 * 其中开放给用户使用的接口为：
 * <ol>
 * <li>{@link AioSession#close()}</li>
 * <li>{@link AioSession#close(boolean)}</li>
 * <li>{@link AioSession#getAttachment()} </li>
 * <li>{@link AioSession#getInputStream()} </li>
 * <li>{@link AioSession#getInputStream(int)} </li>
 * <li>{@link AioSession#getLocalAddress()} </li>
 * <li>{@link AioSession#getRemoteAddress()} </li>
 * <li>{@link AioSession#getSessionID()} </li>
 * <li>{@link AioSession#isInvalid()} </li>
 * <li>{@link AioSession#setAttachment(Object)}  </li>
 *
*/
 public class AioSession<T> {
    /**
     * Session状态:已关闭
     */
    protected static final byte SESSION_STATUS_CLOSED = 1;
    /**
     * Session状态:关闭中
     */
    protected static final byte SESSION_STATUS_CLOSING = 2;
    /**
     * Session状态:正常
     */
    protected static final byte SESSION_STATUS_ENABLED = 3;
    private static final Logger logger = LoggerFactory.getLogger(AioSession.class);


    /**
     * 底层通信channel对象
     */
    protected AsynchronousSocketChannel channel;
    /**
     * 读缓冲。
     * <p>大小取决于AioQuickClient/AioQuickServer设置的setReadBufferSize</p>
     */
    protected VirtualBuffer readBuffer;
    /**
     * 写缓冲
     */
    protected VirtualBuffer writeBuffer;
    /**
     * 会话当前状态
     *
     * @see AioSession#SESSION_STATUS_CLOSED
     * @see AioSession#SESSION_STATUS_CLOSING
     * @see AioSession#SESSION_STATUS_ENABLED
     */
    protected byte status = SESSION_STATUS_ENABLED;
    /**
     * 输出信号量
     */
    private Semaphore semaphore = new Semaphore(1);
    private BufferPage bufferPage;
    /**
     * 附件对象
     */
    private Object attachment;
    /**
     * 响应消息缓存队列。
     * <p>长度取决于AioQuickClient/AioQuickServer设置的setWriteQueueSize</p>
     */
    private ReadCompletionHandler<T> readCompletionHandler;
    private WriteCompletionHandler<T> writeCompletionHandler;
    private IoServerConfig<T> ioServerConfig;
    private InputStream inputStream;
    private BufferOutputStream outputStream;

    /**
     * @param channel
     * @param config
     * @param readCompletionHandler
     * @param writeCompletionHandler
     * @param bufferPage             是否服务端Session
     */
    AioSession(AsynchronousSocketChannel channel,
               IoServerConfig<T> config,
               ReadCompletionHandler<T> readCompletionHandler,
               WriteCompletionHandler<T> writeCompletionHandler,
               BufferPage bufferPage) {
        this.channel = channel;
        this.bufferPage = bufferPage;
        this.readCompletionHandler = readCompletionHandler;
        this.writeCompletionHandler = writeCompletionHandler;
        this.ioServerConfig = config;
//        this.bufferPage = bufferPage;

        this.readBuffer = bufferPage.allocate(config.getReadBufferSize());
        outputStream = new BufferOutputStream(bufferPage,   new Function<BlockingQueue<VirtualBuffer>, Void>() {
            @Override
            public Void apply(BlockingQueue<VirtualBuffer> var) {
                if (!semaphore.tryAcquire()) {
                    return null;
                }
                AioSession.this.writeBuffer = var.poll();
                if (writeBuffer == null) {
                    semaphore.release();
                } else {
                    continueWrite(writeBuffer);
                }
                return null;
            }
        });
        //触发状态机
        config.getProcessor().stateEvent(this, StateMachineEnum.NEW_SESSION, null);
    }
    /**
     * 初始化AioSession
     */
    void initSession() {
        continueRead();
    }

    /**
     * 触发AIO的写操作,
     * <p>需要调用控制同步</p>
     */
    void writeToChannel() {
        if (writeBuffer != null && writeBuffer.buffer().hasRemaining()) {
            continueWrite(writeBuffer);
            return;
        }
        if (writeBuffer != null) {
            writeBuffer.clean();
        }
//        writeBuffer = outputStream.bufList.poll();

        if (writeBuffer != null) {
            continueWrite(writeBuffer);
            return;
        }
        semaphore.release();
        //此时可能是Closing或Closed状态
        if (status != SESSION_STATUS_ENABLED) {
            close();
            return;
        }
        //也许此时有新的消息通过write方法添加到writeCacheQueue中
        if (!outputStream.isClosed()) {
            outputStream.flush();
        }
        bufferPage.clean();
    }


    /**
     * 内部方法：触发通道的读操作
     *
     * @param buffer
     */
    protected final void readFromChannel0(ByteBuffer buffer) {
        channel.read(buffer, this, readCompletionHandler);
    }

    /**
     * 内部方法：触发通道的写操作
     */
    protected final void writeToChannel0(ByteBuffer buffer) {
        channel.write(buffer, this, writeCompletionHandler);
    }

    public final BufferOutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * 强制关闭当前AIOSession。
     * <p>若此时还存留待输出的数据，则会导致该部分数据丢失</p>
     */
    public final void close() {
        close(true);
    }

    /**
     * 是否立即关闭会话
     *
     * @param immediate true:立即关闭,false:响应消息发送完后关闭
     */
    public synchronized void close(boolean immediate) {
        //status == SESSION_STATUS_CLOSED说明close方法被重复调用
//        new Throwable().printStackTrace();
        if (status == SESSION_STATUS_CLOSED) {
            logger.warn("ignore, session:{} is closed:", getSessionID());
            return;
        }
        status = immediate ? SESSION_STATUS_CLOSED : SESSION_STATUS_CLOSING;
        if (immediate) {
            try {
                if (!outputStream.isClosed()) {
                    outputStream.close();
                }
                outputStream = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
            readBuffer.clean();
            readBuffer = null;
            if (writeBuffer != null) {
//                logger.info("AioSession:{} 回收writeInBuf:{}", this.hashCode(), writeBuffer.hashCode() + "" + writeBuffer);
                writeBuffer.clean();
                writeBuffer = null;
            }
            try {
                channel.shutdownInput();
            } catch (IOException e) {
                logger.debug(e.getMessage(), e);
            }
            try {
                channel.shutdownOutput();
            } catch (IOException e) {
                logger.debug(e.getMessage(), e);
            }
            try {
                channel.close();
            } catch (IOException e) {
                logger.debug("close session exception", e);
            }
            ioServerConfig.getProcessor().stateEvent(this, StateMachineEnum.SESSION_CLOSED, null);
            bufferPage.clean();
        } else if ((writeBuffer == null || !writeBuffer.buffer().hasRemaining()) && !outputStream.hasData()) {
            close(true);
        } else {
            ioServerConfig.getProcessor().stateEvent(this, StateMachineEnum.SESSION_CLOSING, null);
            if(!outputStream.isClosed()) {
                outputStream.flush();
            }
        }
    }

    /**
     * 获取当前Session的唯一标识
     */
    public final String getSessionID() {
        return "aioSession-" + hashCode();
    }

    /**
     * 当前会话是否已失效
     */
    public final boolean isInvalid() {
        return status != SESSION_STATUS_ENABLED;
    }


    /**
     * 触发通道的读操作，当发现存在严重消息积压时,会触发流控
     */
    void readFromChannel(boolean eof) {
        final ByteBuffer readBuffer = this.readBuffer.buffer();
        readBuffer.flip();
        final MessageProcessor<T> messageProcessor = ioServerConfig.getProcessor();
        while (readBuffer.hasRemaining() && !isInvalid()) {
            T dataEntry = null;
            try {
                dataEntry = ioServerConfig.getProtocol().decode(readBuffer, this);
            } catch (Exception e) {
                messageProcessor.stateEvent(this, StateMachineEnum.DECODE_EXCEPTION, e);
                throw e;
            }
            if (dataEntry == null) {
                break;
            }

            //处理消息
            try {
                messageProcessor.process(this, dataEntry);
            } catch (Exception e) {
                messageProcessor.stateEvent(this, StateMachineEnum.PROCESS_EXCEPTION, e);
            }
        }
        if (!outputStream.isClosed() && semaphore.availablePermits() > 0) {
            outputStream.flush();
        }


        if (eof || status == SESSION_STATUS_CLOSING) {

            close(false);
            messageProcessor.stateEvent(this, StateMachineEnum.INPUT_SHUTDOWN, null);
            return;
        }
        if (status == SESSION_STATUS_CLOSED) {
            return;
        }

        //数据读取完毕
        if (readBuffer.remaining() == 0) {
            readBuffer.clear();
        } else if (readBuffer.position() > 0) {
            // 仅当发生数据读取时调用compact,减少内存拷贝
            readBuffer.compact();
        } else {
            readBuffer.position(readBuffer.limit());
            readBuffer.limit(readBuffer.capacity());
        }
        continueRead();
        bufferPage.clean();//内存池回收
    }


    protected void continueRead() {
        readFromChannel0(readBuffer.buffer());
    }

    protected void continueWrite(VirtualBuffer writeBuffer) {
        writeToChannel0(writeBuffer.buffer());
    }

    /**
     * 获取附件对象
     *
     * @return
     */
    public final <T> T getAttachment() {
        return (T) attachment;
    }

    /**
     * 存放附件，支持任意类型
     */
    public final <T> void setAttachment(T attachment) {
        this.attachment = attachment;
    }

    /**
     * @see AsynchronousSocketChannel#getLocalAddress()
     */
    public final InetSocketAddress getLocalAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /**
     * @see AsynchronousSocketChannel#getRemoteAddress()
     */
    public final InetSocketAddress getRemoteAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    private void assertChannel() throws IOException {
        if (status == SESSION_STATUS_CLOSED || channel == null) {
            throw new IOException("session is closed");
        }
    }

    IoServerConfig<T> getServerConfig() {
        return this.ioServerConfig;
    }

    /**
     * 获得数据输入流对象。
     * <p>
     * faster模式下调用该方法会触发UnsupportedOperationException异常。
     * </p>
     * <p>
     * MessageProcessor采用异步处理消息的方式时，调用该方法可能会出现异常。
     * </p>
     */
    public InputStream getInputStream() throws IOException {
        return inputStream == null ? getInputStream(-1) : inputStream;
    }

    /**
     * 获取已知长度的InputStream
     *
     * @param length InputStream长度
     */
    public InputStream getInputStream(int length) throws IOException {
        if (inputStream != null) {
            throw new IOException("pre inputStream has not closed");
        }
        if (inputStream != null) {
            return inputStream;
        }
        synchronized (this) {
            if (inputStream == null) {
                inputStream = new InnerInputStream(length);
            }
        }
        return inputStream;
    }


    private class InnerInputStream extends InputStream {
        private int remainLength;

        public InnerInputStream(int length) {
            this.remainLength = length >= 0 ? length : -1;
        }

        @Override
        public int read() throws IOException {
            if (remainLength == 0) {
                return -1;
            }
            ByteBuffer readBuffer = AioSession.this.readBuffer.buffer();
            if (readBuffer.hasRemaining()) {
                remainLength--;
                return readBuffer.get();
            }
            readBuffer.clear();

            try {
                int readSize = channel.read(readBuffer).get();
                readBuffer.flip();
                if (readSize == -1) {
                    remainLength = 0;
                    return -1;
                } else {
                    return read();
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public int available() throws IOException {
            return remainLength == 0 ? 0 : readBuffer.buffer().remaining();
        }

        @Override
        public void close() throws IOException {
            if (AioSession.this.inputStream == InnerInputStream.this) {
                AioSession.this.inputStream = null;
            }
        }
    }
}
