package org.mortbay.jetty.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.mortbay.io.Buffer;
import org.mortbay.io.Buffers;
import org.mortbay.io.nio.NIOBuffer;
import org.mortbay.io.nio.SelectChannelEndPoint;
import org.mortbay.io.nio.SelectorManager;
import org.mortbay.log.Log;

/* ------------------------------------------------------------ */
/**
 * SslHttpChannelEndPoint.
 * 
 * @author Nik Gonzalez <ngonzalez@exist.com>
 * @author Greg Wilkins <gregw@mortbay.com>
 */
public class SslHttpChannelEndPoint extends SelectChannelEndPoint
{
    private static final ByteBuffer[] __NO_BUFFERS={};
    private static final ByteBuffer __EMPTY=ByteBuffer.allocate(0);

    private Buffers _buffers;
    
    private SSLEngine _engine;
    private ByteBuffer _inBuffer;
    private NIOBuffer _inNIOBuffer;
    private ByteBuffer _outBuffer;
    private NIOBuffer _outNIOBuffer;

    private NIOBuffer[] _reuseBuffer=new NIOBuffer[2];    
    private ByteBuffer[] _gather=new ByteBuffer[2];

    private boolean _closing=false;
    
    // ssl
    protected SSLSession _session;
    
    /* ------------------------------------------------------------ */
    public SslHttpChannelEndPoint(Buffers buffers,SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, SSLEngine engine)
            throws SSLException, IOException
    {
        super(channel,selectSet,key);
        _buffers=buffers;
        
        // ssl
        _engine=engine;
        _engine.setUseClientMode(false);
        _session=engine.getSession();

        // TODO pool buffers and use only when needed.
        _outNIOBuffer=(NIOBuffer)buffers.getBuffer(_session.getPacketBufferSize());
        _outBuffer=_outNIOBuffer.getByteBuffer();
        _inNIOBuffer=(NIOBuffer)buffers.getBuffer(_session.getPacketBufferSize());
        _inBuffer=_inNIOBuffer.getByteBuffer();
        
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.mortbay.io.nio.SelectChannelEndPoint#idleExpired()
     */
    protected void idleExpired()
    {
        try
        {
            _selectSet.getManager().dispatch(new Runnable()
            {
                public void run() 
                { 
                    try 
                    {
                        close(); 
                    }
                    catch(Exception e)
                    {
                        Log.ignore(e);
                    }
                }
            });
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }
    }



    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        // TODO - this really should not be done in a loop here - but with async callbacks.
        
        _engine.closeOutbound();
        try
        {   
            int tries=0;

            loop: while (isOpen() && !(_engine.isInboundDone() && _engine.isOutboundDone()))
            {   
                // TODO REMOVE loop check
                if (tries++>100)
                    throw new IllegalStateException();
                
                if (_outNIOBuffer.length()>0)
                {
                    flush();
                    Thread.sleep(100); // TODO yuck
                }

                switch(_engine.getHandshakeStatus())
                {
                    case FINISHED:
                    case NOT_HANDSHAKING:
                        break loop;
                        
                    case NEED_UNWRAP:
                        Buffer buffer =_buffers.getBuffer(_engine.getSession().getApplicationBufferSize());
                        try
                        {
                            ByteBuffer bbuffer = ((NIOBuffer)buffer).getByteBuffer();
                            int f=fill(bbuffer);
                            if (f<=0)
                                break loop;
                            
                        }
                        catch(Exception e)
                        {
                            Log.warn("FIX THIS!: "+e.toString());
                            Log.debug(e);
                        }
                        finally
                        {
                            _buffers.returnBuffer(buffer);
                        }
                        break;
                        
                    case NEED_TASK:
                    {
                        Runnable task;
                        while ((task=_engine.getDelegatedTask())!=null)
                        {
                            task.run();
                        }
                        break;
                    }
                        
                    case NEED_WRAP:
                    {
                        if (_outNIOBuffer.length()>0)
                            flush();
                        
                        SSLEngineResult result=null;
                        try
                        {
                            _outNIOBuffer.compact();
                            int put=_outNIOBuffer.putIndex();
                            _outBuffer.position(put);
                            result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                            _outNIOBuffer.setPutIndex(put+result.bytesProduced());
                        }
                        finally
                        {
                            _outBuffer.position(0);
                        }
                        
                        flush();
                        
                        break;
                    }
                }
            }
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        catch (InterruptedException e)
        {
            Log.ignore(e);
        }
        finally
        {
            super.close();
            
            if (_inNIOBuffer!=null)
                _buffers.returnBuffer(_inNIOBuffer);
            if (_outNIOBuffer!=null)
                _buffers.returnBuffer(_outNIOBuffer);
            if (_reuseBuffer[0]!=null)
                _buffers.returnBuffer(_reuseBuffer[0]);
            if (_reuseBuffer[1]!=null)
                _buffers.returnBuffer(_reuseBuffer[1]);
        }
        
        
    }

    /* ------------------------------------------------------------ */
    /* 
     */
    public int fill(Buffer buffer) throws IOException
    {
        synchronized(buffer)
        {
            ByteBuffer bbuf=extractInputBuffer(buffer);
            int size=buffer.length();

            try
            {
                fill(bbuf);

                int tries=0;
                loop: while (true)
                {
                    // TODO REMOVE loop check
                    if (tries++>100)
                    {
                        throw new IllegalStateException();
                    }
                    
                    if (_outNIOBuffer.length()>0)
                        flush();
                    
                    switch(_engine.getHandshakeStatus())
                    {
                        case FINISHED:
                        case NOT_HANDSHAKING:
                            break loop;

                        case NEED_UNWRAP:
                            if(fill(bbuf)<=0)
                                break loop;
                            break;

                        case NEED_TASK:
                        {
                            Runnable task;
                            while ((task=_engine.getDelegatedTask())!=null)
                            {
                                task.run();
                            }
                            break;
                        }

                        case NEED_WRAP:
                        {
                            SSLEngineResult result=null;
                            synchronized(_outBuffer)
                            {
                                try
                                {
                                    _outNIOBuffer.compact();
                                    int put=_outNIOBuffer.putIndex();
                                    _outBuffer.position();
                                    result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                                    _outNIOBuffer.setPutIndex(put+result.bytesProduced());
                                }
                                finally
                                {
                                    _outBuffer.position(0);
                                }
                            }

                            flush();

                            break;
                        }
                    }
                }
            }
            catch(SSLException e)
            {
                Log.warn(e.toString());
                Log.debug(e);
                throw e;
            }
            finally
            {
                buffer.setPutIndex(bbuf.position());
                bbuf.position(0);
            }

            return buffer.length()-size; 
        }
    }

    /* ------------------------------------------------------------ */
    public int flush(Buffer buffer) throws IOException
    {
        return flush(buffer,null,null);
    }


    /* ------------------------------------------------------------ */
    /*     
     */
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        int consumed=0;
        int available=header.length();
        if (buffer!=null)
            available+=buffer.length();
        SSLEngineResult result;
        
        int tries=0;
        loop: while (true)
        {
            // TODO REMOVE loop check
            if (tries++>100)
            {
                throw new IllegalStateException();
            }
            
            if (_outNIOBuffer.length()>0)
            {
                flush();
                
                // TODO is this too aggressive?
                if (_outNIOBuffer.length()>0)
                    return consumed;
            }
            
            switch(_engine.getHandshakeStatus())
            {
                case FINISHED:
                case NOT_HANDSHAKING:

                    if (available==0)
                        break loop;
                        
                    int c=(header!=null && buffer!=null)?wrap(header,buffer):wrap(header);
                    if (c>0)
                    {
                        consumed+=c;
                        available-=c;
                    }
                    else if (c<0)
                    {
                        return consumed>0?consumed:-1;
                    }
                    
                    break;

                case NEED_UNWRAP:
                    Buffer buf =_buffers.getBuffer(_engine.getSession().getApplicationBufferSize());
                    try
                    {
                        ByteBuffer bbuf = ((NIOBuffer)buf).getByteBuffer();
                        if (fill(bbuf)<=0)
                            break loop;
                    }
                    finally
                    {
                        _buffers.returnBuffer(buf);
                    }
                    
                    break;

                case NEED_TASK:
                {
                    Runnable task;
                    while ((task=_engine.getDelegatedTask())!=null)
                        task.run();
                    break;
                }

                case NEED_WRAP:
                {
                    synchronized(_outBuffer)
                    {
                        try
                        {
                            _outNIOBuffer.compact();
                            int put=_outNIOBuffer.putIndex();
                            _outBuffer.position();
                            result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                            _outNIOBuffer.setPutIndex(put+result.bytesProduced());
                        }
                        finally
                        {
                            _outBuffer.position(0);
                        }
                    }

                    flush();

                    break;
                }
            }
        }
        
       
        return consumed;
    }
    
    
    /* ------------------------------------------------------------ */
    public void flush() throws IOException
    {
        while (_outNIOBuffer.length()>0)
        {
            int flushed=super.flush(_outNIOBuffer);
            if (flushed==0)
            {
                Thread.yield();
                flushed=super.flush(_outNIOBuffer);
                if (flushed==0)
                    return;
            }
        }
    }

    /* ------------------------------------------------------------ */
    private ByteBuffer extractInputBuffer(Buffer buffer)
    {
        assert buffer instanceof NIOBuffer;
        NIOBuffer nbuf=(NIOBuffer)buffer;
        ByteBuffer bbuf=nbuf.getByteBuffer();
        bbuf.position(buffer.putIndex());
        return bbuf;
    }
    
    /* ------------------------------------------------------------ */
    private ByteBuffer extractOutputBuffer(Buffer buffer,int n)
    {
        NIOBuffer nBuf=null;

        if (buffer.buffer() instanceof NIOBuffer)
        {
            nBuf=(NIOBuffer)buffer.buffer();
            return nBuf.getByteBuffer();
        }
        else
        {
            if (_reuseBuffer[n]==null)
                _reuseBuffer[n] = (NIOBuffer)_buffers.getBuffer(_session.getApplicationBufferSize());
            NIOBuffer buf = _reuseBuffer[n];
            buf.clear();
            buf.put(buffer);
            return buf.getByteBuffer();
        }
    }

    /* ------------------------------------------------------------ */
    private int fill(ByteBuffer buffer) throws IOException
    {
        int in_len=0;

        if (_inNIOBuffer.hasContent())
            _inNIOBuffer.compact();
        else 
            _inNIOBuffer.clear();

        while (_inNIOBuffer.space()>0)
        {
            int len=super.fill(_inNIOBuffer);
            if (len<=0)
            {
                if (len==0 || in_len>0)
                    break;
                return -1;
            }
            in_len+=len;
        }
        
        if (_inNIOBuffer.length()==0)
            return 0;

        SSLEngineResult result;
        try
        {
            _inBuffer.position(_inNIOBuffer.getIndex());
            _inBuffer.limit(_inNIOBuffer.putIndex());
            result=_engine.unwrap(_inBuffer,buffer);
            _inNIOBuffer.skip(result.bytesConsumed());
        }
        finally
        {
            _inBuffer.position(0);
            _inBuffer.limit(_inBuffer.capacity());
        }
        

        switch(result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                Log.warn("unswarp {}",result);
                
            case OK:
                return result.bytesProduced();
            case CLOSED:
                return result.bytesProduced()>0?result.bytesProduced():-1;

            default:
                Log.warn("unwrap "+result);
            throw new IOException(result.toString());
        }
    }

    
    private int wrap(Buffer header, Buffer buffer) throws IOException
    {
        SSLEngineResult result=null;
        
        _gather[0]=extractOutputBuffer(header,0);
        synchronized(_gather[0])
        {
            _gather[0].position(header.getIndex());
            _gather[0].limit(header.putIndex());

            _gather[1]=extractOutputBuffer(buffer,1);

            synchronized(_gather[1])
            {
                _gather[1].position(buffer.getIndex());
                _gather[1].limit(buffer.putIndex());

                synchronized(_outBuffer)
                {
                    int consumed=0;
                    try
                    {
                        _outNIOBuffer.clear();
                        _outBuffer.position(0);
                        _outBuffer.limit(_outBuffer.capacity());
                        
                        result=_engine.wrap(_gather,_outBuffer);
                        _outNIOBuffer.setGetIndex(0);
                        _outNIOBuffer.setPutIndex(result.bytesProduced());
                        consumed=result.bytesConsumed();
                    }
                    finally
                    {
                        _outBuffer.position(0);

                        if (consumed>0 && header!=null)
                        {
                            int len=consumed<header.length()?consumed:header.length();
                            header.skip(len);
                            consumed-=len;
                            _gather[0].position(0);
                            _gather[0].limit(_gather[0].capacity());
                        }
                        if (consumed>0 && buffer!=null)
                        {
                            int len=consumed<buffer.length()?consumed:buffer.length();
                            buffer.skip(len);
                            consumed-=len;
                            _gather[1].position(0);
                            _gather[1].limit(_gather[1].capacity());
                        }
                        assert consumed==0;
                    }
                }
            }
        }
        

        switch(result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                Log.warn("unswarp {}",result);
                
            case OK:
                return result.bytesConsumed();
            case CLOSED:
                return result.bytesConsumed()>0?result.bytesConsumed():-1;

            default:
                Log.warn("wrap "+result);
            throw new IOException(result.toString());
        }
    }
    
    private int wrap(Buffer header) throws IOException
    {
        SSLEngineResult result=null;
        _gather[0]=extractOutputBuffer(header,0);
        synchronized(_gather[0])
        {
            _gather[0].position(header.getIndex());
            _gather[0].limit(header.putIndex());

            int consumed=0;
            synchronized(_outBuffer)
            {
                try
                {
                    _outNIOBuffer.clear();
                    _outBuffer.position(0);
                    _outBuffer.limit(_outBuffer.capacity());
                    result=_engine.wrap(_gather[0],_outBuffer);
                    _outNIOBuffer.setGetIndex(0);
                    _outNIOBuffer.setPutIndex(result.bytesProduced());
                    consumed=result.bytesConsumed();
                }
                finally
                {
                    _outBuffer.position(0);

                    if (consumed>0 && header!=null)
                    {
                        int len=consumed<header.length()?consumed:header.length();
                        header.skip(len);
                        consumed-=len;
                        _gather[0].position(0);
                        _gather[0].limit(_gather[0].capacity());
                    }
                    assert consumed==0;
                }
            }
        }
        switch(result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                Log.warn("unswarp {}",result);
                
            case OK:
                return result.bytesConsumed();
            case CLOSED:
                return result.bytesConsumed()>0?result.bytesConsumed():-1;

            default:
                Log.warn("wrap "+result);
            throw new IOException(result.toString());
        }
    }

    
    /* ------------------------------------------------------------ */
    public boolean isBufferingInput()
    {
        return _inNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingOutput()
    {
        return _outNIOBuffer.hasContent() || _closing;
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferred()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public SSLEngine getSSLEngine()
    {
        return _engine;
    }
    
    
}
