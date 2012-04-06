package com.ctc.wstx.io;

import java.io.*;

import com.ctc.wstx.api.ReaderConfig;

/**
 * Simple {@link InputStream} implementation that is used to "unwind" some
 * data previously read from an input stream; so that as long as some of
 * that data remains, it's returned; but as long as it's read, we'll
 * just use data from the underlying original stream. 
 * This is similar to {@link java.io.PushbackInputStream}, but here there's
 * only one implicit pushback, when instance is constructed.
 */
public final class MergedStream
    extends InputStream
{
    final ReaderConfig mConfig;

    final InputStream mIn;

    byte[] mData;

    int mPtr;

    final int mEnd;

    public MergedStream(ReaderConfig cfg,
                        InputStream in, byte[] buf, int start, int end)
    {
        mConfig = cfg;
        mIn = in;
        mData = buf;
        mPtr = start;
        mEnd = end;
    }

    public int available()
        throws IOException
    {
        if (mData != null) {
            return mEnd - mPtr;
        }
        return mIn.available();
    }

    public void close()
        throws IOException
    {
        freeMergedBuffer();
        mIn.close();
    }

    public void mark(int readlimit)
    {
        if (mData == null) {
            mIn.mark(readlimit);
        }
    }
    
    public boolean markSupported()
    {
        /* Only supports marks past the initial rewindable section...
         */
        return (mData == null) && mIn.markSupported();
    }
    
    public int read()
        throws IOException
    {
        if (mData != null) {
            int c = mData[mPtr++] & 0xFF;
            if (mPtr >= mEnd) {
                freeMergedBuffer();
            }
            return c;
        }
        return mIn.read();
    }
    
    public int read(byte[] b)
        throws IOException
    {
        return read(b, 0, b.length);
    }

    public int 	read(byte[] b, int off, int len)
        throws IOException
    {
        if (mData != null) {
            int avail = mEnd - mPtr;
            if (len > avail) {
                len = avail;
            }
            System.arraycopy(mData, mPtr, b, off, len);
            mPtr += len;
            if (mPtr >= mEnd) {
                freeMergedBuffer();
            }
            return len;
        }
        return mIn.read(b, off, len);
    }

    public void reset()
        throws IOException
    {
        if (mData == null) {
            mIn.reset();
        }
    }

    public long skip(long n)
        throws IOException
    {
        long count = 0L;

        if (mData != null) {
            int amount = mEnd - mPtr;

            if (amount > n) { // all in pushed back segment?
                mPtr += (int) n;
                return n;
            }
            freeMergedBuffer();
            count += amount;
            n -= amount;
        }

        if (n > 0) {
            count += mIn.skip(n);
        }
        return count;
    }

    private void freeMergedBuffer()
    {
        if (mData != null) {
            byte[] data = mData;
            mData = null;
            if (mConfig != null) {
                mConfig.freeFullBBuffer(data);
            }
        }
    }
}
