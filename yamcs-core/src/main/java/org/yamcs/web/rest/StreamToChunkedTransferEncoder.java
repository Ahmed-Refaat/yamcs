package org.yamcs.web.rest;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.web.HttpException;
import org.yamcs.web.HttpServerHandler;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFuture;

/**
 * Reads a yamcs stream and maps it directly to an output buffer. If that buffer grows larger
 * than the treshold size for one chunk, this will cause a chunk to be written out.
 * Could maybe be replaced by using built-in netty functionality, but would need to investigate.
 */
public abstract class StreamToChunkedTransferEncoder extends RestStreamSubscriber {
    
    private static final Logger log = LoggerFactory.getLogger(StreamToChunkedTransferEncoder.class);
    private static final int CHUNK_TRESHOLD = 8096;
    
    private RestRequest req;
    
    private ByteBuf buf;
    protected ByteBufOutputStream bufOut;
    private ChannelFuture lastChannelFuture;
    
    protected String contentType;
    protected boolean failed = false;
    
    public StreamToChunkedTransferEncoder(RestRequest req, String contentType) throws HttpException {
        super();
        this.req = req;
        this.contentType = contentType;
        resetBuffer();
        lastChannelFuture = HttpServerHandler.startChunkedTransfer(req.getChannelHandlerContext(), req.getHttpRequest(), contentType);
    }
    
    protected void resetBuffer() {
        buf = req.getChannelHandlerContext().alloc().buffer();
        bufOut = new ByteBufOutputStream(buf);
    }
    
    protected void closeBufferOutputStream() throws IOException {
        bufOut.close();
    }

    @Override
    public void processTuple(Stream stream, Tuple tuple) {
        if (failed) {
            log.warn("Already failed. Ignoring tuple");
            return;
        }
        try {
            processTuple(tuple, bufOut);
            if (buf.readableBytes() >= CHUNK_TRESHOLD) {
                closeBufferOutputStream();
                lastChannelFuture = HttpServerHandler.writeChunk(req.getChannelHandlerContext(), buf);
                resetBuffer();
            }
        } catch (IOException e) {
            log.error("Closing stream due to IO error", e);
            failed = true;
            stream.close();
        }
    }
    
    public abstract void processTuple(Tuple tuple, ByteBufOutputStream bufOut) throws IOException;

    @Override
    public void streamClosed(Stream stream) {
        if (failed) {
            log.warn("Closing channel because transfer failed");
            req.getChannelHandlerContext().channel().close();
            return;
        }
        try {
            closeBufferOutputStream();
            if (buf.readableBytes() > 0) {
                lastChannelFuture = HttpServerHandler.writeChunk(req.getChannelHandlerContext(), buf);
            }
            HttpServerHandler.stopChunkedTransfer(req.getChannelHandlerContext(), req.getHttpRequest(), lastChannelFuture);
        } catch (IOException e) {
            log.error("Could not write final chunk of data", e);
        }
    }
}
